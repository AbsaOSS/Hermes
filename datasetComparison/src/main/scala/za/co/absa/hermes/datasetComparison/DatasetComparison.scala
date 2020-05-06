/*
 * Copyright 2019 ABSA Group Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package za.co.absa.hermes.datasetComparison

import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.sql.{Column, DataFrame, SparkSession}
import za.co.absa.hermes.datasetComparison.cliUtils.CliOptions
import za.co.absa.hermes.datasetComparison.config.DatasetComparisonConfig
import za.co.absa.hermes.utils.HelperFunctions

/**
 * Class that is the brain of the DatasetComparison module. This class should be used in case of using DatasetComparison
 * as a library. In case of running the DatasetComparison as SparkJob, please use the DatasetComparisonJob.
 *
 * @param cliOptions Config object holding run based configurable parameters.
 * @param config Config object holding project based configurable parameters. Difference to the cliOptions is that these
 *               are meant to stay the same for the project, while cliOptions change for each test
 * @param sparkSession Implicit spark session.
 */
class DatasetComparison(cliOptions: CliOptions,
                        config: DatasetComparisonConfig,
                        optionalSchema: Option[StructType] = None)
                       (implicit sparkSession: SparkSession) {

  /**
   * Case class created for the single purpose of holding a pair of reference and tested data in any form together.
   * This can be a pair of DataFrames or Longs for purposes of holding count of rows. Keeping the classes the same
   * type is essential so we are able to use the same set of methods on both.
   *
   * @param reference Reference Data
   * @param actual Actual/Tested Data
   * @tparam T Any type that needs to be represented
   */
  private case class ComparisonPair[+T](reference: T, actual: T)

  /**
   * Runs the comparison and returns the ComparisonResult object with all the data needed about the final state of the
   * comparison.
   *
   * @return ComparisonObject with final state of the comparison ran.
   */
  def compare: ComparisonResult = {
    val testedDF = ComparisonPair(cliOptions.referenceOptions.loadDataFrame, cliOptions.newOptions.loadDataFrame)
    val rowCounts = ComparisonPair(testedDF.reference.count(), testedDF.actual.count())

    optionalSchema match {
      case Some(schema) => checkSchemas(testedDF, schema)
      case None => checkSchemas(testedDF)
    }

    val selector: List[Column] = SchemaUtils.getDataFrameSelector(optionalSchema.getOrElse(testedDF.reference.schema))
    val dfsSorted = ComparisonPair(
      SchemaUtils.alignSchema(testedDF.reference, selector),
      SchemaUtils.alignSchema(testedDF.actual, selector)
    )

    val cmpUniqueColumn: String = generateUniqueColumnName(dfsSorted.actual.columns, "HermesDatasetComparisonUniqueId")

    val dfsWithKey = ComparisonPair(
      addKeyColumn(selector, dfsSorted.reference, cmpUniqueColumn),
      addKeyColumn(selector, dfsSorted.actual, cmpUniqueColumn)
    )

    val duplicateCounts = handleDuplicates(dfsWithKey, cmpUniqueColumn)

    val dfsExcepted = ComparisonPair(
      dfsWithKey.reference.except(dfsWithKey.actual),
      dfsWithKey.actual.except(dfsWithKey.reference)
    )

    val exceptedCount = ComparisonPair(dfsExcepted.reference.count(), dfsExcepted.actual.count())
    val passedCount = rowCounts.reference - exceptedCount.reference

    val resultDF: Option[DataFrame] = exceptedCount.reference + exceptedCount.actual match {
      case 0 => None
      case _ => Some(createDiffDataFrame(cliOptions.outPath, cmpUniqueColumn, dfsExcepted))
    }
    val diffCount: Long = resultDF.map(_.count).getOrElse(0)

    ComparisonResult(
      rowCounts.reference,
      rowCounts.actual,
      duplicateCounts.reference,
      duplicateCounts.actual,
      passedCount,
      selector,
      resultDF,
      diffCount,
      cliOptions.rawOptions
    )
  }

  /**
   * Creates DataFrame that has the original data and differences that were found in error column.
   *
   * @param path Path where the difference will be written to
   * @param dataFrames Pair of relative complements of reference and actual data
   */
  private def createDiffDataFrame(path: String,
                                  cmpUniqueColumn: String,
                                  dataFrames: ComparisonPair[DataFrame]): DataFrame = {
    val joinedData: DataFrame = joinTwoDataFrames(dataFrames, cmpUniqueColumn)

    // Flatten data
    val flatteningFormula = HelperFunctions.flattenSchema(dataFrames.reference)
    val flatExpected: DataFrame = dataFrames.reference.select(flatteningFormula: _*)
    val flatActual: DataFrame = dataFrames.actual.select(flatteningFormula: _*)

    val joinedFlatDataWithoutErrCol: DataFrame = joinTwoDataFrames(ComparisonPair(flatExpected, flatActual), cmpUniqueColumn)
    val joinedFlatDataWithErrCol = joinedFlatDataWithoutErrCol.withColumn(config.errorColumnName, lit(Array[String]()))

    val columns: Array[String] = flatExpected.columns.filterNot(_ == cmpUniqueColumn)
    val flatDataWithErrors: DataFrame = findDifferences(columns, joinedFlatDataWithErrCol)

    // Using the hash key, join the original data and error column from the flat data.
    joinedData.as("df1")
      .join(flatDataWithErrors.as("df2"), Seq(cmpUniqueColumn))
      .select("df1.*", s"df2.${config.errorColumnName}")
      .drop(cmpUniqueColumn)
  }

  /**
   * Performs a check if the schemas of two data frames are actually the same.
   *
   * @param testedDF Comparison pair of two DataFrames whose schema will be tested
   */
  private def checkSchemas(testedDF: ComparisonPair[DataFrame]): Unit = {
    val expectedSchema: StructType = getSchemaWithoutMetadata(testedDF.reference.schema)
    val actualSchema: StructType = getSchemaWithoutMetadata(testedDF.actual.schema)

    if (!SchemaUtils.isSameSchema(expectedSchema, actualSchema)) {
      val diffSchema = SchemaUtils.diffSchema(expectedSchema, actualSchema) ++
        SchemaUtils.diffSchema(actualSchema, expectedSchema)
      throw SchemasDifferException(cliOptions.referenceOptions.path, cliOptions.newOptions.path, diffSchema.mkString("\n"))
    }
  }

  /**
   * Performs a check if the schemas of two data frames are supersets of schema provided..
   *
   * @param testedDF Comparison pair of two DataFrames whose schema will be tested
   * @param schema Schema that needs to be a subset of schemas provided by data sets
   */
  def checkSchemas(testedDF: ComparisonPair[DataFrame], schema: StructType): Unit = {
    val expectedSchema: StructType = getSchemaWithoutMetadata(testedDF.reference.schema)
    val actualSchema: StructType = getSchemaWithoutMetadata(testedDF.actual.schema)

    if (!(SchemaUtils.doesSchemaComply(schema, actualSchema) && SchemaUtils.doesSchemaComply(schema, expectedSchema))) {
      val diffSchema = SchemaUtils.diffSchema(schema, actualSchema) ++
        SchemaUtils.diffSchema(schema, expectedSchema)
      throw BadProvidedSchema(cliOptions.referenceOptions.path, cliOptions.newOptions.path, diffSchema.mkString("\n"))
    }
  }

  /**
   * Handles duplicates in a sense that this method looks for them. Then based on the configuration if found, throws an
   * error, does deduplication or just passes through.
   *
   * @param dfsWithKey DataFrame pair where both have appended unique key
   * @return
   */
  private def handleDuplicates(dfsWithKey: ComparisonPair[DataFrame], cmpUniqueColumn: String): ComparisonPair[Long] = {
    def write(df: DataFrame, duplicates: DataFrame, path: String): Unit = {
      df.alias("original")
        .join(duplicates, Seq(cmpUniqueColumn), "inner")
        .select("original.*")
        .drop(cmpUniqueColumn)
        .write
        .format("parquet")
        .save(path)
    }

    val dfsDuplicates: ComparisonPair[Option[DataFrame]] = ComparisonPair(
      checkForDuplicateRows(dfsWithKey.reference, cmpUniqueColumn),
      checkForDuplicateRows(dfsWithKey.actual, cmpUniqueColumn)
    )

    val duplicateCounts: ComparisonPair[Long] = ComparisonPair(
      dfsDuplicates.reference.map(_.count()).getOrElse(0),
      dfsDuplicates.actual.map(_.count()).getOrElse(0)
    )

    if ((duplicateCounts.reference + duplicateCounts.actual) > 0 && !config.allowDuplicates) {
      dfsDuplicates.reference.foreach(x => write(dfsWithKey.reference, x, s"${cliOptions.outPath}/refDuplicates"))
      dfsDuplicates.actual.foreach(x => write(dfsWithKey.actual, x, s"${cliOptions.outPath}/newDuplicates"))

      throw DuplicateRowsInDF(cliOptions.outPath)
    }

    duplicateCounts
  }

  /**
   * Adds prefixes to the two supplied data sets and then joins them using full join on keys. All columns will be
   * renamed based on the prefix passed in through application properties for expected and actual data frames.
   *
   * @param dataFrames Pair of data frames to be joined
   * @return Returns new data frame containing both data frames with renamed columns and joined on keys.
   */
  private def joinTwoDataFrames(dataFrames: ComparisonPair[DataFrame], cmpUniqueColumn: String): DataFrame = {
    val dfNewExpected = renameColumns(dataFrames.reference, config.expectedPrefix, cmpUniqueColumn)
    val dfNewColumnsActual = renameColumns(dataFrames.actual, config.actualPrefix, cmpUniqueColumn)
    dfNewExpected.join(dfNewColumnsActual, Seq(cmpUniqueColumn), "full")
  }

  /**
   * Finds errors in data. Uses columns as a basis for column names and prepends them with {actualPrefix} and
   * {expectedPrefix}. Return a data frame with added error column
   *
   * @param columns column names to be traversed and compared
   * @param joinedFlatDataWithErrCol flattened and joined expected and actual data
   * @return DataFrame with errors in error column
   */
  private def findDifferences(columns: Array[String], joinedFlatDataWithErrCol: DataFrame): DataFrame = {
    val tmpColumnName: String = generateUniqueColumnName(columns, "HermesDatasetComparisonTmp")

    columns.foldLeft(joinedFlatDataWithErrCol) { (data, column) =>
      data.withColumnRenamed(config.errorColumnName, tmpColumnName)
        .withColumn(config.errorColumnName, concat(
          when(col(s"${config.actualPrefix}_$column") === col(s"${config.expectedPrefix}_$column") or
            (col(s"${config.expectedPrefix}_$column").isNull and
              col(s"${config.actualPrefix}_$column").isNull),
            lit(Array[String]()))
            .otherwise(array(lit(column))), col(tmpColumnName)))
        .drop(tmpColumnName)
    }
  }

  /**
   * Most simple method to generate temporary column name. As base "HermesDatasetComparisonTmp" will be used and a
   * number will be appended to the end with underscore. Returning "HermesDatasetComparisonTmp_X" in the end. Where X is
   * the number
   * @param columns Array of column names
   * @return Returns a unique column name
   */
  private def generateUniqueColumnName(columns: Array[String], base: String = "tmp"): String = {
    @scala.annotation.tailrec
    def appendNumberAndTest(name: String, condition: String => Boolean, count: Int = 0): String = {
      val newName = s"${name}_$count"
      if (condition(newName)) {
        appendNumberAndTest(name, condition, count + 1)
      }
      else newName
    }

    if (columns.contains(base)) {
      appendNumberAndTest(base, { x: String => columns.contains(x) })
    } else {
      base
    }
  }

  /**
   * Adds a key column to the DataFrame passed
   *
   * @param selector Selector of columns in case key columns are not defined
   * @param df DataFrame to have key column appended
   * @return Returns a DataFrame with key column appended
   */
  private def addKeyColumn(selector: List[Column], df: DataFrame, cmpUniqueColumn: String): DataFrame = {
    if (cliOptions.keys.nonEmpty) {
      df.withColumn(cmpUniqueColumn, md5(concat_ws("|", cliOptions.keys.map(col).toSeq: _*)))
    } else {
      df.withColumn(cmpUniqueColumn, md5(concat_ws("|", selector: _*)))
    }
  }

  /**
   * Checks for duplicate rows based on combination of keys. If a set of keys is present more then once, then data
   * is evaluated as having duplicates and error is thrown.
   *
   * @param df Data frame that will be evaluated for duplicate rows
   */
  private def checkForDuplicateRows(df: DataFrame, cmpUniqueColumn: String): Option[DataFrame] = {
    val duplicates = df
      .groupBy(cmpUniqueColumn)
      .count()
      .filter("`count` >= 2")

    if (duplicates.count() == 0) {
      None
    } else {
      Some(duplicates)
    }
  }

  /**
   * Renames all columns expect the keys and appends prefix to them.
   *
   * @param dataSet Dataset that needs columns renamed
   * @param prefix Prefix that will be put in front of column names
   * @return New DataFrame with renamed columns
   */
  private def renameColumns(dataSet: DataFrame, prefix: String, cmpUniqueColumn: String): DataFrame = {
    val renamedColumns = dataSet.columns.map { column =>
      if (cmpUniqueColumn.equals(column)) {
        dataSet(column)
      } else {
        dataSet(column).as(s"${prefix}_$column")
      }
    }

    dataSet.select(renamedColumns: _*)
  }

  /**
   * Returns data frame schema without metadata
   *
   * @return Schema without metadata
   */
  private def getSchemaWithoutMetadata(schema: StructType): StructType = {
    StructType(schema.map{ f => StructField(f.name, f.dataType, f.nullable) })
  }
}
