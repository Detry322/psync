name := "round"

version := "0.1-SNAPSHOT"

scalaVersion := "2.11.4"

scalacOptions in Compile ++= Seq(
    "-unchecked",
    "-deprecation",
    "-feature",
    "-language:implicitConversions",
    "-Xmax-classfile-name", "110"//,
//    "-Ymacro-debug-lite"
//    "-Xlog-implicits"
//    "-Xlog-implicit-conversions"
)

libraryDependencies ++=  Seq(
    "org.scala-lang" % "scala-reflect" % scalaVersion.value,
    //"org.scala-lang" % "scala-actors" % scalaVersion.value
    "org.scala-lang.modules" %% "scala-xml" % "1.0.2",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2",
    "org.scalatest" %% "scalatest" % "2.2.1" % "test",
    "org.scalacheck" %% "scalacheck" % "1.11.6" % "test",
    //"org.scala-lang" %% "scala-pickling" % "0.10.0-SNAPSHOT",
    "org.scala-lang" %% "scala-pickling" % "0.9.0",
    "io.netty" % "netty-all" % "4.0.23.Final",
    "io.github.dzufferey" %% "scala-arg" % "0.1-SNAPSHOT",
    "io.github.dzufferey" %% "report" % "0.1-SNAPSHOT",
    "io.github.dzufferey" %% "misc-scala-utils" % "0.1-SNAPSHOT"//,
//    "commons-io" % "commons-io" % "2.4"
)

resolvers += Resolver.sonatypeRepo("snapshots")

