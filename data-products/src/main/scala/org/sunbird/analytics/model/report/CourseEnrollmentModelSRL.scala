package org.sunbird.analytics.model.report

import java.text.SimpleDateFormat
import java.util.Calendar

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Encoders, SQLContext, SparkSession}
import org.ekstep.analytics.framework._
import org.ekstep.analytics.framework.util.{JSONUtils, JobLogger}
import org.ekstep.analytics.model.ReportConfig
import org.sunbird.cloud.storage.conf.AppConf
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.StructType
import org.sunbird.analytics.job.report.BaseCourseMetricsSRL
import org.sunbird.analytics.job.report.BaseCourseMetricsOutputSRL
import org.sunbird.analytics.util.CourseUtils

case class CourseEnrollmentOutputSRL(date: String, courseName: String, batchName: String, status: String, enrollmentCount: BigInt, completionCount: BigInt, slug: String, reportName: String) extends AlgoOutput with Output
case class ESResponse(participantCount: BigInt, completedCount: BigInt, batchId: String)

object CourseEnrollmentModelSRL extends BaseCourseMetricsSRL[Empty, BaseCourseMetricsOutputSRL, CourseEnrollmentOutputSRL, CourseEnrollmentOutputSRL] with Serializable {

  implicit val className: String = "org.ekstep.analytics.model.CourseEnrollmentModelSRL"
  override def name: String = "CourseEnrollmentModelSRL"
  val sunbirdCoursesKeyspace: String = AppConf.getConfig("course.metrics.cassandra.sunbirdCoursesKeyspace")

    override def algorithm(events: RDD[BaseCourseMetricsOutputSRL], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[CourseEnrollmentOutputSRL] = {
    implicit val sqlContext = new SQLContext(sc)
    val finalRDD = getCourseEnrollmentOutputSRL(events)
    val date = (new SimpleDateFormat("dd-MM-yyyy")).format(Calendar.getInstance().getTime)
    finalRDD.map(f => CourseEnrollmentOutputSRL(date,f._1.courseName,f._1.batchName,f._1.status,
      f._2.getOrElse(ESResponse(0,0,"")).participantCount,f._2.getOrElse(ESResponse(0,0,"")).completedCount,f._1.slug,"course_enrollments"))
  }

  override def postProcess(data: RDD[CourseEnrollmentOutputSRL], config: Map[String, AnyRef])(implicit sc: SparkContext, fc: FrameworkContext): RDD[CourseEnrollmentOutputSRL] = {
    implicit val sqlContext = new SQLContext(sc)
    if (data.count() > 0) {
      val configMap = config("reportConfig").asInstanceOf[Map[String, AnyRef]]
      val reportConfig = JSONUtils.deserialize[ReportConfig](JSONUtils.serialize(configMap))

      import sqlContext.implicits._
      reportConfig.output.map { f =>
        val df = data.toDF().na.fill(0L)
        CourseUtils.postDataToBlob(df, f,config)
      }
    } else {
      JobLogger.log("No data found", None, Level.INFO)
    }
    data
  }

  def getCourseEnrollmentOutputSRL(events: RDD[BaseCourseMetricsOutputSRL],loadData: (SparkSession, Map[String, String], String, StructType) => DataFrame)(implicit sc: SparkContext, fc: FrameworkContext, sqlContext: SQLContext, spark: SparkSession): RDD[(BaseCourseMetricsOutputSRL, Option[ESResponse])] =  {

//    val batchId = events.collect().map(f => f.batchId)
//    val courseId = events.collect().map(f => f.courseId)
    val userEnrolledCount = getUserEnrollmentDF(loadData)
    val participantCount = userEnrolledCount.groupBy("batchid").count()
    val completedCount = userEnrolledCount.groupBy("batchid").filter(userEnrolledCount("completionpercentage").equalTo("100")).count()

    val courseCounts = getCourseBatchCounts(JSONUtils.serialize(courseId),JSONUtils.serialize(batchId))
    val baseCourseMetricsOutput = events.map(f=> (f.batchId,f))

    val encoder = Encoders.product[ESResponse]
    val courseInfo = courseCounts.as[ESResponse](encoder).rdd

    val courses = courseInfo.map(f => (f.batchId, f))
    val finalRDD = baseCourseMetricsOutput.leftOuterJoin(courses)
    finalRDD.map(f => f._2)
  }

  def getCourseBatchCounts(courseIds: String, batchIds: String)(implicit sc: SparkContext, sqlContext: SQLContext) : DataFrame = {
    val request = s"""{
                     |  "query": {
                     |    "bool": {
                     |      "filter": [
                     |        {
                     |          "terms": {
                     |            "id.raw": $batchIds
                     |          }
                     |        }
                     |      ]
                     |    }
                     |  }
                     |}""".stripMargin

    sqlContext.sparkSession.read.format("org.elasticsearch.spark.sql")
      .option("query", request)
      .option("pushdown", "true")
      .option("es.nodes", AppConf.getConfig("es.host"))
      .option("es.port", AppConf.getConfig("es.port"))
      .option("es.scroll.size", AppConf.getConfig("es.scroll.size"))
      .option("inferSchema", "true")
      .load("course-batch").select(
      col("participantCount"), col("completedCount"), col("id").as("batchId")
    )
  }

  def getUserEnrollmentDF(loadData: (SparkSession, Map[String, String], String, StructType) => DataFrame)(implicit spark: SparkSession): DataFrame = {
    loadData(spark, Map("table" -> "user_enrolments", "keyspace" -> sunbirdCoursesKeyspace), "org.apache.spark.sql.cassandra", new StructType())
      .select(col("batchid"), col("completionpercentage"))
  }
}
