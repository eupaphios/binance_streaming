package org.binance

import org.apache.spark.sql.functions.{col, from_json, from_unixtime, window}
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.types.TimestampType
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.binance.data.Schema.tradeStreamsSchema
import org.binance.spark.VWAPCombiner

import java.util.concurrent.TimeUnit

/**
  * //https://databricks.com/blog/2017/04/26/processing-data-in-apache-kafka-with-structured-streaming-in-apache-spark-2-2.html
  */
object StructuredStreaming {

  def toConsole(df: DataFrame, intervalSeconds: Long) = {
    df
      .writeStream
      //      .outputMode("complete")
      .format("console")
      .trigger(Trigger.ProcessingTime(intervalSeconds, TimeUnit.SECONDS))
      .option("truncate",false)
      .start()
  }

  def aggDfToConsole(df: DataFrame, intervalSeconds: Long, is_last: Boolean = false) = {

    if (is_last) {
      df
        .writeStream
        .outputMode("complete")
        .format("console")
        .trigger(Trigger.ProcessingTime(intervalSeconds, TimeUnit.SECONDS))
        .option("truncate",false)
        .start()
        .awaitTermination()
    } else {
      df
        .writeStream
        .outputMode("complete")
        .format("console")
        .trigger(Trigger.ProcessingTime(intervalSeconds, TimeUnit.SECONDS))
        .option("truncate",false)
        .start()
    }

  }
  def main(args: Array[String]): Unit = {


    val vwapCombiner = new VWAPCombiner()
    val spark = SparkSession
      .builder
      .appName("BinanceStreaming")
      .config("spark.sql.caseSensitive" , "True")
      .config("spark.sql.streaming.checkpointLocation","/tmp/blockchain-streaming/sql-streaming-checkpoint")
      .master("local[4]")
      .getOrCreate()

//    val ssc = new StreamingContext(spark.sparkContext,Seconds(15))


    spark.sparkContext.setLogLevel("ERROR")

    val tradeStream = spark
      .readStream
      .format("kafka")
      .option("subscribe", "binance")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .load
      .selectExpr("cast(value as string) as value") //casting binary values into string
      .select(from_json(col("value"), tradeStreamsSchema).alias("tmp")).select("tmp.*")
      .withColumn("p",  col("p").cast("double"))
      .withColumn("q",  col("q").cast("double"))
      .withColumn("pq", col("p") * col("q"))
      .withColumn("T", from_unixtime(col("T").cast ("bigint")/1000).cast(TimestampType))
//      .withColumn("T", unix_timestampmp($"T", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").cast(TimestampType))
      .withWatermark("T", "5 seconds")
    //          .as[TradeStreams] //Enable to do any Dataset operations

    tradeStream.printSchema()

    tradeStream.createOrReplaceTempView("trade_stream")


    val sql = "SELECT s as key, SUM(pq) as sum_pq, SUM(q) as sum_q FROM trade_stream GROUP BY s"

    val vwapOverSqlStatement = spark
      .sql(sql)
      .withColumn("vwapOverSqlStatement",  col("sum_pq") /  col("sum_q"))
      .withColumn("value",  col("vwapOverSqlStatement").cast("string"))

    vwapOverSqlStatement.printSchema()

    val vwap = tradeStream
      .groupBy(
        window(col("T"), "10 seconds", "5 seconds"),
        col("s")
      ).sum("pq", "q")

    //(run-main-1) org.apache.spark.sql.AnalysisException: Multiple streaming aggregations are not supported with streaming DataFrames/Datasets;;
    //.groupBy("s")
    //.sum("sum(pq)", "sum(q)")


    vwapOverSqlStatement
      .writeStream
      .format("kafka")
      .outputMode("complete")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .option("topic","vwap")
      .option("checkpointLocation", "/tmp/blockchain-streaming/sql-streaming-checkpoint/vwap/")
      .start()

    spark
      .readStream
      .format("kafka")
      .option("subscribe","vwap")
      .option("kafka.bootstrap.servers", "localhost:9092")
      .load
      .selectExpr("cast(value as string) as vwapFromSparkStreaming") //casting binary values into string
      .writeStream
//      .outputMode("complete")
      .format("console")
      //.trigger(Trigger.Continuous(batchTimeInSeconds, TimeUnit.SECONDS))
      .option("truncate",false)
      .start()
      .awaitTermination()
  }
}


