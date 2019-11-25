package za.co.absa.hermes.infoFileComparison

import com.typesafe.config.{Config, ConfigFactory}
import za.co.absa.atum.model.{Checkpoint, ControlMeasure, ControlMeasureMetadata, Measurement}

/**
  * Object holding extensions of Atum Models for comparison purposes.
  */
object AtumModelUtils {
  private val conf: Config = ConfigFactory.load
  private val stdVersionKey = conf.getString("info-file-comparison.atum-models.stdVersionKey")
  private val confVersionKey = conf.getString("info-file-comparison.atum-models.confVersionKey")
  private val stdNameKey = conf.getString("info-file-comparison.atum-models.stdNameKey")
  private val confNameKey = conf.getString("info-file-comparison.atum-models.confNameKey")
  private val stdAppIdKey = conf.getString("info-file-comparison.atum-models.stdAppIdKey")
  private val confAppIdKey = conf.getString("info-file-comparison.atum-models.confAppIdKey")

  /**
    * ControlMeasure's class extension adding compareWith
    * @param controlMeasure Control Measure instance
    */
  implicit class ControlMeasureOps (controlMeasure: ControlMeasure){
    /**
      * Compare this ControlMeasure with the one passed in
      * @param otherControlMeasure  Second control measure
      * @return                     Returns a list of model differences
      */
    def compareWith(otherControlMeasure: ControlMeasure): List[ModelDifference[_]] = {
      val metadataDifferences = controlMeasure.metadata.compareWith(otherControlMeasure.metadata, "metadata")
      val checkpointsDifferences = controlMeasure.checkpoints
        .zipWithIndex
        .foldLeft(List[ModelDifference[_]]()) {
          case (agg, (checkpointValue, index)) =>
            checkpointValue.compareWith(otherControlMeasure.checkpoints(index), s"checkpoints[$index]") ::: agg
        }
      metadataDifferences ::: checkpointsDifferences
    }
  }

  /**
    * ControlMeasureMetadata's class extension adding compareWith
    * @param metadata ControlMeasureMetadata instance
    */
  implicit class ControlMeasureMetadataOps (metadata: ControlMeasureMetadata){

    /**
      * Compare this ControlMeasureMetadata with the one passed in
      * @param otherMetadata  Second control measure metadata
      * @param curPath        Path to the ControlMeasureMetadata through the model
      * @return               Returns a list of model differences
      */
    def compareWith(otherMetadata: ControlMeasureMetadata, curPath: String): List[ModelDifference[_]] = {
      val diffs = simpleCompare(
          metadata.sourceApplication,
          otherMetadata.sourceApplication, s"$curPath.sourceApplication"
        ) ::
        simpleCompare(metadata.country, otherMetadata.country, s"$curPath.country") ::
        simpleCompare(metadata.historyType, otherMetadata.historyType, s"$curPath.historyType") ::
        simpleCompare(metadata.dataFilename, otherMetadata.dataFilename, s"$curPath.dataFilename") ::
        simpleCompare(metadata.sourceType, otherMetadata.sourceType, s"$curPath.sourceType") ::
        simpleCompare(metadata.version, otherMetadata.version, s"$curPath.version") ::
        simpleCompare(metadata.informationDate, otherMetadata.informationDate, s"$curPath.informationDate") ::
        Nil

      val additionalInfoDiff = additionalInfoComparison(metadata.additionalInfo,
        otherMetadata.additionalInfo,
        s"$curPath.additionalInfo")

      diffs.flatten ::: additionalInfoDiff
    }

    /**
      * Compare additional information from ControlMeasureMetadata
      *
      * @param was      Value it had in reference
      * @param is       Value it has now
      * @param curPath  Path to the AdditionalInfo through the model
      * @return         Returns a list of model differences
      */
    private def additionalInfoComparison(was: Map[String, String],
                                         is: Map[String,String],
                                         curPath: String): List[ModelDifference[_]] = {
      // TODO #2 This is ignored because we cannot control the IDs and others which makes the size unstable.
      val keysToIgnore = Seq(
        "conform_input_dir_size",
        "std_input_dir_size",
        "std_output_dir_size",
        "conform_output_dir_size"
      )

      was.flatMap {
        case (wasKey, wasValue) if wasKey == confVersionKey =>
          logVersionAndContinue(confNameKey, wasValue, is.get(wasKey))
          None
        case (wasKey, wasValue) if wasKey == stdVersionKey =>
          logVersionAndContinue(stdNameKey, wasValue, is.get(wasKey))
          None
        case (wasKey, _) if wasKey == stdAppIdKey || wasKey == confAppIdKey =>
          None
        case (wasKey, wasValue) if keysToIgnore.contains(wasKey) =>
          logIgnoredKey(wasKey, wasValue, is.get(wasKey))
          None
        case (wasKey, wasValue) =>
          is.get(wasKey) match {
            case Some(isValue) if wasValue != isValue => Some(ModelDifference(s"$curPath.$wasKey", wasValue, isValue))
            case None                                 => Some(ModelDifference(s"$curPath.$wasKey", wasValue, "Null"))
            case _                                    => None
          }
      }.toList.sortBy({ f: ModelDifference[String] => f.path })
    }

    private def logVersionAndContinue(name: String, refVersion: String, newVersion: Option[String]): Unit = {
      scribe.info(s"$name versions is:")
      scribe.info(s"Reference - $refVersion")
      scribe.info(s"New - ${newVersion.getOrElse("NOT SPECIFIED")}")
    }

    private def logIgnoredKey(name: String, wasValue: String, maybeValue: Option[String]): Unit = {
      scribe.info(s"$name key ignored. Values:")
      scribe.info(s"Reference - $wasValue")
      scribe.info(s"New - ${maybeValue.getOrElse("NOT SPECIFIED")}")
    }
  }

  /**
    * Checkpoint's class extension adding compareWith
    * @param checkpoint Checkpoint instance
    */
  implicit class CheckpointOps (checkpoint: Checkpoint) {
    /**
      * Compare this Checkpoint with the one passed in
      * @param otherCheckpoint Second checkpoint
      * @param curPath Path to the checkpoint through the model
      * @return Returns a list of model differences
      */
    def compareWith(otherCheckpoint: Checkpoint, curPath: String): List[ModelDifference[_]] = {
      val diffs =
        simpleCompare(checkpoint.name, otherCheckpoint.name, s"$curPath.name") ::
          simpleCompare(checkpoint.workflowName, otherCheckpoint.workflowName, s"$curPath.workflowName") ::
          simpleCompare(checkpoint.order, otherCheckpoint.order, s"$curPath.order") :: Nil

      val controls = checkpoint.controls.zipWithIndex.foldLeft(List[ModelDifference[_]]()) {
        case (agg, (checkpointValue, index)) =>
          val nextPath = s"$curPath.controls[$index]"
          checkpointValue.compareWith(otherCheckpoint.controls(index), nextPath) ::: agg
      }

      diffs.flatten ::: controls
    }
  }

  /**
    * Measurement's class extension adding compareWith
    * @param measurement Measurement instance
    */
  implicit class MeasurementOps (measurement: Measurement){
    /**
      * Compare this Measurement with the one passed in
      * @param otherMeasurement Second measurement
      * @param curPath Path to the measurement through the model
      * @return Returns a list of model differences
      */
    def compareWith(otherMeasurement: Measurement, curPath: String): List[ModelDifference[_]] ={
      val diffs =
        simpleCompare(measurement.controlName,otherMeasurement.controlName, s"$curPath.controlName") ::
          simpleCompare(measurement.controlType, otherMeasurement.controlType, s"$curPath.controlType") ::
          simpleCompare(measurement.controlCol, otherMeasurement.controlCol, s"$curPath.controlCol") ::
          simpleCompare(measurement.controlValue, otherMeasurement.controlValue, s"$curPath.controlValue") ::
          Nil
      diffs.flatten
    }
  }

  /**
    *
    * @param first First value or the ref value
    * @param other Second value or the new value
    * @param curPath Current path to the values, so they are traceable
    * @tparam T Any value that has == implemented
    * @return Returns an Option of ModelDifference. If None is returned, there is no difference in the two values
    */
  private def simpleCompare[T](first: T, other: T, curPath: String): Option[ModelDifference[T]] = {
    if (first != other) {
      Some(ModelDifference(curPath, first, other))
    }
    else {
      None
    }
  }
}

