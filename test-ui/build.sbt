ThisBuild / scalaVersion := "2.13.1"
ThisBuild / organization := "com.colingodsey"

val AkkaV = "2.6.4"

lazy val root = (project in file("."))
  .settings(
    name := "test-ui",
    libraryDependencies += "com.typesafe.akka" %% "akka-actor"      % AkkaV,
    libraryDependencies += "com.typesafe.akka" %% "akka-remote"     % AkkaV,
    libraryDependencies += "com.typesafe.akka" %% "akka-http"       % "10.1.11",
    libraryDependencies += "org.openjfx"        % "javafx-graphics" % "14",
    libraryDependencies += "org.json4s"        %% "json4s-native"   % "3.6.7",
  )