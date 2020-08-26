
enablePlugins(ScalaJSPlugin)

ThisBuild / scalaVersion := "2.13.2"

lazy val common = crossProject(JSPlatform, JVMPlatform).in(file("common"))
    .settings(name := "print-server-common")
    .settings(Build.commonSettings: _*)
    .jsSettings(Build.jsSettings: _*)
    .jvmSettings(Build.jvmSettings: _*)

lazy val commonJS = common.js

lazy val commonJVM = common.jvm

lazy val server = project.in(file("server"))
    .settings(name := "print-server-jvm")
    .settings(Build.commonSettings: _*)
    .settings(Build.jvmSettings: _*)
    .dependsOn(commonJVM)

lazy val debug = project.in(file("debug"))
    .settings(name := "print-debug-jvm")
    .settings(Build.commonSettings: _*)
    .settings(Build.jvmSettings: _*)
    .settings(libraryDependencies += "org.openjfx" % "javafx-graphics" % "14")
    .dependsOn(commonJVM, server)


/*
lazy val webPlayer = project.in(file("web-player"))
    .enablePlugins(ScalaJSPlugin)
    .settings(
      name := "web-player",
      scalaJSUseMainModuleInitializer := true,
      scalaJSStage in Global := FastOptStage
    )
    .settings(Build.commonSettings: _*)
    .settings(Build.jsSettings: _*)
    .dependsOn(commonJS)*/

Build.buildSettings
