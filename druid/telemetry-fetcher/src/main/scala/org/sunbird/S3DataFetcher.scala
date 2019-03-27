package org.sunbird

import java.util.Date

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.jets3t.service.impl.rest.httpclient.RestS3Service
import org.jets3t.service.security.AWSCredentials
import org.joda.time.{Days, LocalDate}
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.sunbird.AzureDataFetcher
//import org.sunbird.EventsFetcher

class DataFetcherException(msg: String, ex: Exception = null) extends Exception(msg, ex) {}


object S3DataFetcher {
  @transient val dateFormat: DateTimeFormatter = DateTimeFormat.forPattern("yyyy-MM-dd").withZoneUTC();

  @throws(classOf[DataFetcherException])
  def fetchBatchData[T](search: fetch)(implicit mf: Manifest[T], sc: SparkContext): RDD[T] = {
    if (search.queries.isEmpty) {
      if (search.`type`.equals("none")) return sc.emptyRDD[T]
      throw new DataFetcherException("Data fetch configuration not found")
    }
    val keys: Array[String] = search.`type`.toLowerCase() match {
      case "s3" =>
        getObjectKeys(search.queries.get);
      case "azure" =>
        AzureDataFetcher.getObjectKeys(search.queries.get);
        //search.queries.get.map { x => x.file.getOrElse("") }.filterNot { x => x == null };
      case _ =>
        throw new DataFetcherException("Unknown fetcher type found");
    }
    if (null == keys || keys.length == 0) {
      return sc.parallelize(Seq[T](), 10);
    }
    val isString = mf.runtimeClass.getName.equals("java.lang.String");
    sc.textFile(keys.mkString(","), 10).map { line => {
      try {
        if(isString){

          line.asInstanceOf[T]
        }else{
          println("Not a string")
          line.asInstanceOf[T]
        }
//        if(isString) line.asInstanceOf[T] else JSONUtils.deserialize[T](line);
        //        if(isString) line.asInstanceOf[T] else JSONUtils.deserialize[T](line);
      } catch {
        case ex: Exception =>
          null.asInstanceOf[T]
      }
    }
    }.filter { x => x != null };
  }

  def getObjectKeys(queries: Array[Query]): Array[String] = {

    val keys = for (query <- queries) yield {
      val paths = if (query.folder.isDefined && query.endDate.isDefined && query.folder.getOrElse("false").equals("true")) {
        Array("s3n://" + getBucket(query.bucket) + "/" + getPrefix(query.prefix) + query.endDate.get)
      } else if (query.creationDate.isDefined) {
        searchByCreatedDate(query.bucket.get, query.prefix.get, query.creationDate.get)
      } else {
        println("else")
        getKeys(query);
      }
      if (query.excludePrefix.isDefined) {
        paths.filter { x => !x.contains(query.excludePrefix.get) }
      } else {
        paths
      }
    }
    keys.flatMap { x => x.map { x => x } }
  }

  private def getKeys(query: Query): Array[String] = {
    search(getBucket(query.bucket), getPrefix(query.prefix), query.startDate, query.endDate, query.delta, query.datePattern.getOrElse("yyyy-MM-dd")).filterNot { x => x.isEmpty() }
  }

  private def getBucket(bucket: Option[String]): String = {
    bucket.getOrElse("ekstep-prod-data-store");
  }

  private def getPrefix(prefix: Option[String]): String = {
    prefix.getOrElse("raw/");
  }

  def searchByCreatedDate(bucketName: String, prefix: String, creationDate: String, pattern: String = "yyyy-MM-dd"): Array[String] = {
    null
  }

  def search(bucketName: String, prefix: String, fromDate: Option[String] = None, toDate: Option[String] = None, delta: Option[Int] = None, pattern: String = "yyyy-MM-dd"): Array[String] = {
    val from = fromDate;
    if (from.nonEmpty) {
      val dates = getDatesBetween(from.get, toDate, pattern);
      val paths = for (date <- dates) yield {
        getPath(bucketName, prefix + date);
      }
      paths.flatMap { x => x.map { x => x } };
    } else {
      getPath(bucketName, prefix);
    }
  }

  def getAllKeys(bucketName: String, prefix: String): Array[String] = {
    val awsCredentials = new AWSCredentials(System.getenv("aws_storage_key"), System.getenv("aws_storage_secret"));
    val s3Service = new RestS3Service(awsCredentials);
    val s3Objects = s3Service.listObjects(bucketName, prefix, null);
    s3Objects.map { x => x.getKey }
  }

  def getPath(bucket: String, prefix: String): Array[String] = {
    getAllKeys(bucket, prefix).map { x => "s3n://" + bucket + "/" + x };
  }

  def getDatesBetween(fromDate: String, toDate: Option[String], pattern: String): Array[String] = {
    val df: DateTimeFormatter = DateTimeFormat.forPattern(pattern).withZoneUTC();
    val to = if (toDate.nonEmpty) df.parseLocalDate(toDate.get) else LocalDate.fromDateFields(new Date);
    val from = df.parseLocalDate(fromDate);
    val dates = datesBetween(from, to);
    dates.map { x => df.print(x) }.toArray;
  }

  def datesBetween(from: LocalDate, to: LocalDate): IndexedSeq[LocalDate] = {
    val numberOfDays = Days.daysBetween(from, to).getDays()
    for (f <- 0 to numberOfDays) yield from.plusDays(f)
  }
}
