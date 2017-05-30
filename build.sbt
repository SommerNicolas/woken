
name          := "woken"

version       := sys.env.get("VERSION")getOrElse("dev")

scalaVersion  := "2.11.8"

val versions = new {
  val woken_messages = "76f49ed"
  val akka = "2.3.14"
  val spray = "1.3.2"
  val spark = "2.0.0"
  val scalaz = "7.1.3"
  val slf4j = "1.7.13"
  val config = "1.2.1"
  val postgres = "9.4-1203-jdbc42"
  val doobie = "0.2.3"
  val snakeyaml = "1.17"
  val scalaTest = "2.2.5"
  val spec2 = "2.3.11"

  //---Bachelor ----//
  val deeplearning4j = "0.8.0"
  val datavec = "0.8.0"

}

libraryDependencies ++= {
  Seq(
    "woken-messages"      %%  "woken-messages"           % versions.woken_messages,
    "io.spray"            %%  "spray-can"                % versions.spray exclude("io.spray", "spray-routing"),
    "io.spray"            %%  "spray-routing-shapeless2" % versions.spray,
    "io.spray"            %%  "spray-json"               % versions.spray,
    "com.typesafe.akka"   %%  "akka-actor"               % versions.akka,
    "com.typesafe.akka"   %%  "akka-remote"              % versions.akka,
    "com.typesafe.akka"   %%  "akka-cluster"             % versions.akka,
    //"org.slf4j"         %%  "slf4j-nop"                % versions.slf4j,
    "org.slf4j"           %   "slf4j-api"                % versions.slf4j,
    //"org.slf4j"         %%  "log4j-over-slf4j"         % versions.slf4j, // For Denodo JDBC driver
    "org.scalaz"          %%  "scalaz-core"              % versions.scalaz,
    "com.typesafe"        %   "config"                   % versions.config,
    "org.postgresql"      %   "postgresql"               % versions.postgres,
    "org.tpolecat"        %%  "doobie-core"              % versions.doobie,
    "com.gettyimages"     %%  "spray-swagger"            % "0.5.0" excludeAll ExclusionRule(organization = "io.spray"),
    "org.webjars"         %   "swagger-ui"               % "2.0.12",
    "org.yaml"            %   "snakeyaml"                % versions.snakeyaml,
    ("org.apache.spark"   %%  "spark-mllib"              % versions.spark).
      exclude("commons-beanutils", "commons-beanutils-core").
      exclude("commons-collections", "commons-collections").
      exclude("commons-logging", "commons-logging").
      exclude("org.apache.ivy", "ivy"),


  //---------- Test libraries -------------------//
    "org.scalatest"       %%  "scalatest"        % versions.scalaTest % "test",
    "org.specs2"          %%  "specs2-core"      % versions.spec2     % "test",
    "com.netaporter"      %%  "pre-canned"       % "0.0.7"            % "test",
    "com.typesafe.akka"   %%  "akka-testkit"     % versions.akka      % "test",
    "io.spray"            %%  "spray-testkit"    % versions.spray     % "test",

    //----------Bachelor libraries--------------//
    "org.deeplearning4j" % "deeplearning4j-core" % versions.deeplearning4j,
    "org.deeplearning4j" % "deeplearning4j-nn" % versions.deeplearning4j,
    "org.deeplearning4j" % "deeplearning4j-modelimport" % versions.deeplearning4j,
    "org.deeplearning4j" % "deeplearning4j-scaleout" % versions.deeplearning4j,
    "org.datavec" % "datavec-api" % versions.datavec,
    "org.datavec" % "datavec-data-image" % versions.datavec,
    "org.slf4j" % "slf4j-api" % versions.slf4j,
    "org.slf4j" % "slf4j-simple" % versions.slf4j
  )
}
//-----Bachelor libraries----//
classpathTypes += "maven-plugin"
libraryDependencies += "org.nd4j" % "nd4j-native" % "0.4-rc3.10" classifier "" classifier "linux-x86_64"
libraryDependencies += "org.nd4j" % "nd4j-jblas" % "0.0.3.5.1"



resolvers += "hbpmip artifactory" at "http://hbps1.chuv.ch/artifactory/libs-release/"

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-Xlint",
  "-Ywarn-dead-code",
  "-language:_",
  "-target:jvm-1.8",
  "-encoding", "UTF-8"
)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

fork in Test := false

parallelExecution in Test := false

Revolver.settings : Seq[sbt.Def.Setting[_]]

fork in run := true

mainClass in Runtime := Some("web.Web")

test in assembly := {} // Do not run tests when building the assembly

assemblyMergeStrategy in assembly := {
  case PathList("org", "apache", "spark", "unused", xs @ _*) => MergeStrategy.first
  case PathList("org", "apache", "hadoop", "yarn", xs @ _*) => MergeStrategy.first
  case PathList("org", "apache", "hadoop", "yarn", xs @ _*) => MergeStrategy.first
  case PathList("org", "aopalliance", xs @ _*) => MergeStrategy.first
  case PathList("javax", "ws", "rs", xs @ _*) => MergeStrategy.first
  case PathList("javax", "inject", xs @ _*) => MergeStrategy.first
  case x =>
    val oldStrategy = (assemblyMergeStrategy in assembly).value
    oldStrategy(x)
}

// TODO Instead of exclude and merge strategy it is safer to use
// Read dependence tree
/*assemblyShadeRules in assembly := Seq(
  ShadeRule.rename("org.apache.commons.io.**" -> "shadeio.@1").inLibrary("commons-io" % "commons-io" % "2.4", ...).inProject
)*/

//net.virtualvoid.sbt.graph.Plugin.graphSettings
