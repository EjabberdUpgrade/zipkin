import com.twitter.sbt.{BuildProperties,PackageDist,GitProject}
import sbt._
import com.twitter.scrooge.ScroogeSBT
import sbt.Keys._

object Zipkin extends Build {

  val CASSIE_VERSION  = "0.25.3"
  val OSTRICH_VERSION = "9.2.1"
  val SCROOGE_VERSION = "3.11.1"
  val TwitterServerVersion = "1.4.0"
  val ZOOKEEPER_VERSION = Map("candidate" -> "0.0.41", "group" -> "0.0.44", "client" -> "0.0.35")
  val ALGEBIRD_VERSION  = "0.1.13"
  val HBASE_VERSION = "0.94.10"

  val finagleVersion = "6.8.1"
  val utilVersion = "6.8.1"
  def finagle(name: String) = "com.twitter" %% ("finagle-" + name) % finagleVersion
  def util(name: String) = "com.twitter" %% ("util-" + name) % utilVersion

  val proxyRepo = Option(System.getenv("SBT_PROXY_REPO"))
  val travisCi = Option(System.getenv("SBT_TRAVIS_CI")) // for adding travis ci maven repos before others
  val cwd = System.getProperty("user.dir")

  lazy val testDependencies = Seq(
    "org.jmock"               %  "jmock"        % "2.4.0" % "test",
    "org.hamcrest"            %  "hamcrest-all" % "1.1"   % "test",
    "cglib"                   %  "cglib"        % "2.2.2" % "test",
    "asm"                     %  "asm"          % "1.5.3" % "test",
    "org.objenesis"           %  "objenesis"    % "1.1"   % "test",
    "org.scalatest"           %% "scalatest"    % "1.9.1" % "test",
    "org.scala-tools.testing" %% "specs"        % "1.6.9" % "test" cross CrossVersion.binaryMapped {
      case "2.9.2" => "2.9.1"
      case "2.10.0" => "2.10"
      case x => x
    },
    "junit" % "junit" % "4.10" % "test"
  )

  def zipkinSettings = Seq(
    organization := "com.twitter",
    version := "1.1.1-SNAPSHOT",
    crossScalaVersions := Seq("2.9.2"),
    scalaVersion := "2.9.2",
    crossPaths := false,            /* Removes Scala version from artifact name */
    fork := true, // forking prevents runaway thread pollution of sbt
    baseDirectory in run := file(cwd), // necessary for forking
    publishTo := Some(Resolver.file("file",  new File(Path.userHome.absolutePath + "/.ivy2/local")))
  )

  // settings from inlined plugins
  def inlineSettings = Seq(
    // inlined parts of sbt-package-dist
    GitProject.gitSettings,
    BuildProperties.newSettings,
    PackageDist.newSettings,

    // modifications and additions
    Seq(
      (exportedProducts in Compile) ~= { products =>
        products.filter { prod =>
          // don't package source or documentation
          prod == (packageSrc in Compile) || prod == (packageDoc in Compile)
        }
      }
    )
  ).flatten

  def defaultSettings = Seq(
    zipkinSettings,
    inlineSettings,
    Project.defaultSettings,
    ZipkinResolver.newSettings
  ).flatten

  // Database drivers
  val anormDriverDependencies = Map(
    "sqlite-memory"     -> "org.xerial"     % "sqlite-jdbc"          % "3.7.2",
    "sqlite-persistent" -> "org.xerial"     % "sqlite-jdbc"          % "3.7.2",
    "h2-memory"         -> "com.h2database" % "h2"                   % "1.3.172",
    "h2-persistent"     -> "com.h2database" % "h2"                   % "1.3.172",
    "postgresql"        -> "postgresql"     % "postgresql"           % "8.4-702.jdbc4", // or "9.1-901.jdbc4",
    "mysql"             -> "mysql"          % "mysql-connector-java" % "5.1.25"
  )

  lazy val zipkin =
    Project(
      id = "zipkin",
      base = file(".")
    ) aggregate(test, queryCore, queryService, common, scrooge, collectorScribe, web, cassandra, anormDB, collectorCore, collectorService, kafka, redis, hbase)

  lazy val test   = Project(
    id = "zipkin-test",
    base = file("zipkin-test"),
    settings = defaultSettings
  ).settings(
    name := "zipkin-test",
    libraryDependencies ++= testDependencies
  ) dependsOn(queryService, collectorService)

  lazy val common =
    Project(
      id = "zipkin-common",
      base = file("zipkin-common"),
      settings = defaultSettings
    ).settings(
      libraryDependencies ++= Seq(
        finagle("ostrich4"),
        finagle("thrift"),
        finagle("zipkin"),
        finagle("exception"),
        "com.twitter" %% "ostrich"           % OSTRICH_VERSION,
        util("core"),
        "com.twitter" %% "algebird-core"     % ALGEBIRD_VERSION,

        "com.twitter.common.zookeeper" % "client"    % ZOOKEEPER_VERSION("client")
      ) ++ testDependencies
    )

  lazy val thriftidl =
    Project(
      id = "zipkin-thrift",
      base = file("zipkin-thrift"),
      settings = defaultSettings
    ).settings(
      // this is a hack to get -idl artifacts for thrift.  Better would be to
      // define a whole new artifact that gets included in the scrooge publish task
      (artifactClassifier in packageSrc) := Some("idl")
    )

  lazy val scrooge =
    Project(
      id = "zipkin-scrooge",
      base = file("zipkin-scrooge"),
      settings = defaultSettings ++ ScroogeSBT.newSettings
    ).settings(
        libraryDependencies ++= Seq(
        finagle("ostrich4"),
        finagle("thrift"),
        finagle("zipkin"),
        "com.twitter" %% "ostrich"           % OSTRICH_VERSION,
        util("core"),
        "com.twitter" %% "algebird-core"     % ALGEBIRD_VERSION,
        "com.twitter" %% "scrooge-core"      % SCROOGE_VERSION,
        "com.twitter" %% "scrooge-serializer" % SCROOGE_VERSION
      ) ++ testDependencies
    ).dependsOn(common)

  lazy val collectorCore = Project(
    id = "zipkin-collector-core",
    base = file("zipkin-collector-core"),
    settings = defaultSettings
  ).settings(
    libraryDependencies ++= Seq(
      finagle("ostrich4"),
      finagle("serversets"),
      finagle("thrift"),
      finagle("zipkin"),
      "com.twitter" %% "ostrich"           % OSTRICH_VERSION,
      "com.twitter" %% "algebird-core"     % ALGEBIRD_VERSION,
      util("core"),
      util("zk"),
      util("zk-common"),
      "com.twitter" %% "twitter-server"    % TwitterServerVersion,

      "com.twitter.common.zookeeper" % "candidate" % ZOOKEEPER_VERSION("candidate"),
      "com.twitter.common.zookeeper" % "group"     % ZOOKEEPER_VERSION("group")
    ) ++ testDependencies
  ).dependsOn(common, scrooge)

  lazy val cassandra = Project(
    id = "zipkin-cassandra",
    base = file("zipkin-cassandra"),
    settings = defaultSettings
  ).settings(
    libraryDependencies ++= Seq(
      "com.twitter"     % "cassie-core"       % CASSIE_VERSION,
      "com.twitter"     % "cassie-serversets" % CASSIE_VERSION,
      util("logging"),
      util("app"),
      "org.iq80.snappy" % "snappy"            % "0.1",
      "com.twitter" %% "scrooge-serializer" % SCROOGE_VERSION
    ) ++ testDependencies,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(scrooge)

  lazy val anormDB = Project(
    id = "zipkin-anormdb",
    base = file("zipkin-anormdb"),
    settings = defaultSettings
  ).settings(
    libraryDependencies ++= Seq(
      "play" %% "anorm" % "2.1-09142012",
      anormDriverDependencies("sqlite-persistent")
    ) ++ testDependencies,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(common, scrooge)

  lazy val queryCore =
    Project(
      id = "zipkin-query-core",
      base = file("zipkin-query-core"),
      settings = defaultSettings
    ).settings(
      libraryDependencies ++= Seq(
        finagle("ostrich4"),
        finagle("serversets"),
        finagle("thrift"),
        finagle("zipkin"),
        "com.twitter" %% "ostrich"           % OSTRICH_VERSION,
        "com.twitter" %% "algebird-core"     % ALGEBIRD_VERSION,
        util("core"),
        util("zk"),
        util("zk-common"),

        "com.twitter.common.zookeeper" % "candidate" % ZOOKEEPER_VERSION("candidate"),
        "com.twitter.common.zookeeper" % "group"     % ZOOKEEPER_VERSION("group")
      ) ++ testDependencies
    ).dependsOn(common, scrooge)

  lazy val queryService = Project(
    id = "zipkin-query-service",
    base = file("zipkin-query-service"),
    settings = defaultSettings
  ).settings(
    libraryDependencies ++= testDependencies,

    PackageDist.packageDistZipName := "zipkin-query-service.zip",
    BuildProperties.buildPropertiesPackage := "com.twitter.zipkin",
    resourceGenerators in Compile <+= BuildProperties.buildPropertiesWrite,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(queryCore, cassandra, redis, anormDB, hbase)

  lazy val collectorScribe =
    Project(
      id = "zipkin-collector-scribe",
      base = file("zipkin-collector-scribe"),
      settings = defaultSettings
    ).settings(
      libraryDependencies ++= Seq(
        "com.twitter" %% "scrooge-serializer" % SCROOGE_VERSION
      ) ++ testDependencies
    ).dependsOn(collectorCore, scrooge)

  lazy val kafka =
    Project(
      id = "zipkin-kafka",
      base = file("zipkin-kafka"),
      settings = defaultSettings
    ).settings(
      libraryDependencies ++= Seq(
        "org.clojars.jasonjckn"      %% "kafka"    % "0.7.2-test1",
      "com.twitter" %% "scrooge-serializer" % SCROOGE_VERSION
      ) ++ testDependencies,
      resolvers ++= (proxyRepo match {
        case None => Seq(
          "clojars" at "http://clojars.org/repo")
        case Some(pr) => Seq() // if proxy is set we assume that it has the artifacts we would get from the above repo
      })
    ).dependsOn(collectorCore, scrooge)

  lazy val collectorService = Project(
    id = "zipkin-collector-service",
    base = file("zipkin-collector-service"),
    settings = defaultSettings
  ).settings(
    libraryDependencies ++= testDependencies,

    PackageDist.packageDistZipName := "zipkin-collector-service.zip",
    BuildProperties.buildPropertiesPackage := "com.twitter.zipkin",
    resourceGenerators in Compile <+= BuildProperties.buildPropertiesWrite,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(collectorCore, collectorScribe, cassandra, kafka, redis, anormDB, hbase)

  lazy val web =
    Project(
      id = "zipkin-web",
      base = file("zipkin-web"),
      settings = defaultSettings
    ).settings(
      libraryDependencies ++= Seq(
        "com.twitter" %% "twitter-server" % "1.3.1",
        "com.github.spullara.mustache.java" % "compiler" % "0.8.13",

        "com.twitter.common.zookeeper" % "server-set" % "1.0.36",

        finagle("exception"),
        finagle("thriftmux"),
        finagle("serversets"),
        finagle("zipkin"),
        "com.twitter" %% "algebird-core"      % ALGEBIRD_VERSION
      ) ++ testDependencies,

      PackageDist.packageDistZipName := "zipkin-web.zip",
      BuildProperties.buildPropertiesPackage := "com.twitter.zipkin",
      resourceGenerators in Compile <+= BuildProperties.buildPropertiesWrite,

      /* Add configs to resource path for ConfigSpec */
      unmanagedResourceDirectories in Test <<= baseDirectory {
        base =>
          (base / "config" +++ base / "src" / "test" / "resources").get
      }
  ).dependsOn(common, scrooge)

  lazy val redis = Project(
    id = "zipkin-redis",
    base = file("zipkin-redis"),
    settings = defaultSettings
  ).settings(
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      finagle("redis"),
      util("logging"),
      "org.slf4j"   %  "slf4j-log4j12"      % "1.6.4" % "runtime",
      "com.twitter" %% "scrooge-serializer" % SCROOGE_VERSION
    ) ++ testDependencies,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(scrooge)

  lazy val hbase = Project(
    id = "zipkin-hbase",
    base = file("zipkin-hbase"),
    settings = defaultSettings
  ).settings(
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "org.apache.hbase"      % "hbase"                 % HBASE_VERSION notTransitive(),
      "org.apache.hbase"      % "hbase"                 % HBASE_VERSION % "test" classifier("tests") classifier(""),
      "com.google.protobuf"   % "protobuf-java"         % "2.4.1",
      "org.apache.hadoop"     % "hadoop-core"           % "1.1.2" notTransitive(),
      "org.apache.hadoop"     % "hadoop-test"           % "1.1.2" % "test",
      "commons-logging"       % "commons-logging"       % "1.1.1",
      "commons-configuration" % "commons-configuration" % "1.6",
      "org.apache.zookeeper"  % "zookeeper"             % "3.4.5" % "runtime" notTransitive(),
      "org.slf4j"             % "slf4j-log4j12"         % "1.6.4" % "runtime",
      util("logging"),
      "com.twitter"           %% "scrooge-serializer"   % SCROOGE_VERSION
    ) ++ testDependencies,

    /* Add configs to resource path for ConfigSpec */
    unmanagedResourceDirectories in Test <<= baseDirectory {
      base =>
        (base / "config" +++ base / "src" / "test" / "resources").get
    }
  ).dependsOn(scrooge)
}

