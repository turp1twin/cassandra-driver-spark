package com.datastax.driver.spark.writer

import java.io.IOException
import java.net.InetAddress

import com.datastax.driver.spark._
import com.datastax.driver.spark.connector.CassandraConnector
import com.datastax.driver.spark.util.{CassandraServer, SparkServer}
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.collection.JavaConversions._

case class KeyValue(key: Int, group: Long, value: String)
case class KeyValueWithConversion(key: String, group: Int, value: Long)

class TableWriterSpec extends FlatSpec with Matchers with BeforeAndAfter with CassandraServer with SparkServer {

  useCassandraConfig("cassandra-default.yaml.template")
  val conn = CassandraConnector(InetAddress.getByName("127.0.0.1"))

  before {
    conn.withSessionDo { session =>
      session.execute("CREATE KEYSPACE IF NOT EXISTS write_test WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 }")
      session.execute("CREATE TABLE IF NOT EXISTS write_test.key_value (key INT, group BIGINT, value TEXT, PRIMARY KEY (key, group))")
      session.execute("CREATE TABLE IF NOT EXISTS write_test.collections (key INT PRIMARY KEY, l list<text>, s set<text>, m map<text, text>)")
      session.execute("CREATE TABLE IF NOT EXISTS write_test.blobs (key INT PRIMARY KEY, b blob)")
      session.execute("TRUNCATE write_test.key_value")
      session.execute("TRUNCATE write_test.collections")
      session.execute("TRUNCATE write_test.blobs")
    }
  }

  "A TableWriter" should "write RDD of tuples" in {
    val col = Seq((1, 1L, "value1"), (2, 2L, "value2"), (3, 3L, "value3"))
    sc.parallelize(col).saveToCassandra("write_test", "key_value", Seq("key", "group", "value"))
    conn.withSessionDo { session =>
      val result = session.execute("SELECT * FROM write_test.key_value").all()
      result should have size 3
      for (row <- result) {
        Some(row.getInt(0)) should contain oneOf(1, 2, 3)
        Some(row.getLong(1)) should contain oneOf(1, 2, 3)
        Some(row.getString(2)) should contain oneOf("value1", "value2", "value3")
      }
    }
  }

  it should "write RDD of tuples applying proper data type conversions" in {
    val col = Seq(("1", "1", "value1"), ("2", "2", "value2"), ("3", "3", "value3"))
    sc.parallelize(col).saveToCassandra("write_test", "key_value")
    conn.withSessionDo { session =>
      val result = session.execute("SELECT * FROM write_test.key_value").all()
      result should have size 3
      for (row <- result) {
        Some(row.getInt(0)) should contain oneOf(1, 2, 3)
        Some(row.getLong(1)) should contain oneOf(1, 2, 3)
        Some(row.getString(2)) should contain oneOf("value1", "value2", "value3")
      }
    }
  }

  it should "write RDD of case class objects" in {
    val col = Seq(KeyValue(1, 1L, "value1"), KeyValue(2, 2L, "value2"), KeyValue(3, 3L, "value3"))
    sc.parallelize(col).saveToCassandra("write_test", "key_value")
    conn.withSessionDo { session =>
      val result = session.execute("SELECT * FROM write_test.key_value").all()
      result should have size 3
      for (row <- result) {
        Some(row.getInt(0)) should contain oneOf(1, 2, 3)
        Some(row.getLong(1)) should contain oneOf(1, 2, 3)
        Some(row.getString(2)) should contain oneOf("value1", "value2", "value3")
      }
    }
  }

  it should "write RDD of case class objects applying proper data type conversions" in {
    val col = Seq(
      KeyValueWithConversion("1", 1, 1L),
      KeyValueWithConversion("2", 2, 2L),
      KeyValueWithConversion("3", 3, 3L))
    sc.parallelize(col).saveToCassandra("write_test", "key_value")
    conn.withSessionDo { session =>
      val result = session.execute("SELECT * FROM write_test.key_value").all()
      result should have size 3
      for (row <- result) {
        Some(row.getInt(0)) should contain oneOf(1, 2, 3)
        Some(row.getLong(1)) should contain oneOf(1, 2, 3)
        Some(row.getString(2)) should contain oneOf("1", "2", "3")
      }
    }
  }

  it should "write collections" in {
    val col = Seq(
      (1, Vector("item1", "item2"), Set("item1", "item2"), Map("key1" -> "value1", "key2" -> "value2")),
      (2, Vector.empty[String], Set.empty[String], Map.empty[String, String]))
    sc.parallelize(col).saveToCassandra("write_test", "collections", Seq("key", "l", "s", "m"))

    conn.withSessionDo { session =>
      val result = session.execute("SELECT * FROM write_test.collections").all()
      result should have size 2
      val rows = result.groupBy(_.getInt(0)).mapValues(_.head)
      val row0 = rows(1)
      val row1 = rows(2)
      row0.getList("l", classOf[String]).toSeq shouldEqual Seq("item1", "item2")
      row0.getSet("s", classOf[String]).toSeq shouldEqual Seq("item1", "item2")
      row0.getMap("m", classOf[String], classOf[String]).toMap shouldEqual Map("key1" -> "value1", "key2" -> "value2")
      row1.isNull("l") shouldEqual true
      row1.isNull("m") shouldEqual true
      row1.isNull("s") shouldEqual true
    }
  }

  it should "write blobs" in {
    val col = Seq((1, Some(Array[Byte](0, 1, 2, 3))), (2, None))
    sc.parallelize(col).saveToCassandra("write_test", "blobs", Seq("key", "b"))
    conn.withSessionDo { session =>
      val result = session.execute("SELECT * FROM write_test.blobs").all()
      result should have size 2
      val rows = result.groupBy(_.getInt(0)).mapValues(_.head)
      val row0 = rows(1)
      val row1 = rows(2)
      row0.getBytes("b").remaining shouldEqual 4
      row1.isNull("b") shouldEqual true
    }
  }

  it should "throw IOException if table is not found" in {
    val col = Seq(("1", "1", "value1"), ("2", "2", "value2"), ("3", "3", "value3"))
    intercept[IOException] {
      sc.parallelize(col).saveToCassandra("write_test", "unknown_table")
    }
  }

}
