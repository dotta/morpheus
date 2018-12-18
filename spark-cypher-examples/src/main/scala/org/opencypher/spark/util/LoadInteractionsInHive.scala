package org.opencypher.spark.util

import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.types.{LongType, StringType, StructField, StructType}
import org.opencypher.spark.api.CAPSSession

object LoadInteractionsInHive {

  val databaseName = "customers"
  val baseTableName = s"$databaseName.csv_input"

  def load(show: Boolean = false)(implicit session: CAPSSession): DataFrame = {

    val datafile = getClass.getResource("/customer-interactions/csv/customer-interactions.csv").toURI.getPath
    val structType = StructType(Seq(
      StructField("interactionId", LongType, nullable = false),
      StructField("date", StringType, nullable = false),
      StructField("customerIdx", LongType, nullable = false),
      StructField("empNo", LongType, nullable = false),
      StructField("empName", StringType, nullable = false),
      StructField("type", StringType, nullable = false),
      StructField("outcomeScore", StringType, nullable = false),
      StructField("accountHolderId", StringType, nullable = false),
      StructField("policyAccountNumber", StringType, nullable = false),
      StructField("customerId", StringType, nullable = false),
      StructField("customerName", StringType, nullable = false)
    ))

    val baseTable: DataFrame = session.sparkSession.read
      .format("csv")
      .option("header", "true")
      .schema(structType)
      .load(datafile)

    if (show) baseTable.show()

    session.sql(s"DROP DATABASE IF EXISTS $databaseName CASCADE")
    session.sql(s"CREATE DATABASE $databaseName")

    baseTable.write.saveAsTable(s"$baseTableName")

    // Create views for nodes
    createView(baseTableName, "interactions", true, "interactionId", "date", "type", "outcomeScore")
    createView(baseTableName, "customers", true, "customerIdx", "customerId", "customerName")
    createView(baseTableName, "account_holders", true, "accountHolderId")
    createView(baseTableName, "policies", true, "policyAccountNumber")
    createView(baseTableName, "customer_reps", true, "empNo", "empName")

    // Create views for relationships
    createView(baseTableName, "has_customer_reps", false, "interactionId", "empNo")
    createView(baseTableName, "has_customers", false, "interactionId", "customerIdx")
    createView(baseTableName, "has_policies", false, "interactionId", "policyAccountNumber")
    createView(baseTableName, "has_account_holders", false, "interactionId", "accountHolderId")

    baseTable
  }

  def createView(fromTable: String, viewName: String, distinct: Boolean, columns: String*)
    (implicit session: CAPSSession): Unit = {
    val distinctString = if (distinct) "DISTINCT" else ""

    session.sql(
      s"""
         |CREATE VIEW $databaseName.${viewName}_SEED AS
         | SELECT $distinctString ${columns.mkString(", ")}
         | FROM $fromTable
         | WHERE date < '2017-01-01'
      """.stripMargin)

    session.sql(
      s"""
         |CREATE VIEW $databaseName.${viewName}_DELTA AS
         | SELECT $distinctString ${columns.mkString(", ")}
         | FROM $fromTable
         | WHERE date >= '2017-01-01'
      """.stripMargin)
  }

}