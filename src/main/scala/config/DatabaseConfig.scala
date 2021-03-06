package config

import dao._
import doobie.imports._
import scalaz.effect.IO
import com.typesafe.scalalogging.slf4j._
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json.deserializationError

trait DatabaseConfig[D <: DAL] {
  def dal: D
}

trait DoobieDatabaseConfig[D <: DAL] extends DatabaseConfig[D] {
  def xa: Transactor[IO]
}

/**
  * Configuration for the database storing the results of the calculation launched locally.
  */
//Based on play-slick driver loader
object ResultDatabaseConfig extends DoobieDatabaseConfig[JobResultsDAL] {
  val logger = Logger(LoggerFactory.getLogger("database"))

  def testConnection(xa: Transactor[IO], jdbcUrl: String) = try {
    sql"select 1".query[Int].unique.transact(xa).unsafePerformIO
  } catch {
    case e: java.sql.SQLException =>
      logger.error(s"Cannot connect to $jdbcUrl", e)
  }

  import Config._
  val config = dbConfig(jobs.resultDb)
  import config._
  lazy val xa = DriverManagerTransactor[IO](
    jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword
  )
  testConnection(xa, jdbcUrl)

  lazy val dal = new NodeDAL(xa)
}

/**
  * Configuration for the federation database (Denodo) gathering the results from the other nodes.
  */
//Based on play-slick driver loader
object FederationDatabaseConfig {
  import Config._
  import ResultDatabaseConfig.testConnection

  val config: Option[DatabaseConfig[JobResultsDAL]] = if (!jobs.jobsConf.hasPath("federationDb")) None else
    Some(new DoobieDatabaseConfig[JobResultsDAL] {
      val config = dbConfig(jobs.jobsConf.getString("federationDb"))
      import config._
      lazy val xa = DriverManagerTransactor[IO](
        jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword
      )
      testConnection(xa, jdbcUrl)

      lazy val dal = new FederationDAL(xa)
    })
}

/**
  * Configuration for the LDSM database containing the raw data to display on .
  */
object LdsmDatabaseConfig extends DatabaseConfig[LdsmDAL] {

  import Config.dbConfig
  import Config.defaultSettings._

  val config = dbConfig(defaultDb)
  import config._

  lazy val dal = new LdsmDAL(jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword, mainTable)
}

/**
  * Configuration for the Meta database
  */
object MetaDatabaseConfig extends DatabaseConfig[MetaDAL] {
  val logger = Logger(LoggerFactory.getLogger("database"))

  import Config.dbConfig
  import Config.defaultSettings._

  val config = dbConfig(defaultMetaDb)
  import config._
  import spray.json.{JsObject, JsArray}

  lazy val dal = new MetaDAL(jdbcDriver, jdbcUrl, jdbcUser, jdbcPassword, mainTable)
  lazy val groups = dal.getMetaData

  def getMetaData(variables: Seq[String]): JsObject = {

    /**
      * Parse the tree of groups to find the variables meta data!
      * Temporary... We need to separate groups from variable meta!
      * @return
      */
    def getVariableMetaData(variable: String, groups: JsObject): Option[JsObject] = {

      if (groups.fields.contains("variables")) {
        groups.fields.get("variables").get match {
          case a: JsArray =>
            a.elements.find(v => v.asJsObject.fields.get("code") match {
              case Some(stringValue) => stringValue.convertTo[String] == variable
              case None => false
            }) match {
              case Some(value) => return Some(value.asJsObject)
              case None => None
            }
          case _ => deserializationError("JsArray expected")
        }
      }

      if (groups.fields.contains("groups")) {
        groups.fields.get("groups").get match {
          case a : JsArray =>
            return a.elements.toStream.map(g => getVariableMetaData(variable, g.asJsObject)).find(o => !o.isEmpty) match {
              case Some(variable: Option[JsObject]) => variable
              case None => None
            }
          case _ => deserializationError("JsArray expected")
        }
      }

      None
    }

    new JsObject(variables.map(v =>
      v -> (getVariableMetaData(v, groups) match {
        case Some(m) => m
        case None => {
          logger.error(s"Cannot not find metadata for " + v)
          JsObject.empty
        }
      })
    ).toMap)
  }
}
