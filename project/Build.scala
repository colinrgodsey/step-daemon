/*
 * Copyright 2017 Colin Godsey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import sbt.Keys._
import sbt._

import org.scalajs.sbtplugin.ScalaJSPlugin
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._

import sbtassembly.AssemblyKeys._

object Build {
  val AkkaV = "2.6.4"

  def buildSettings = Seq(
    name := "print-server",

    publish := {},
    publishLocal := {},

    version in ThisBuild ~= (version in LocalRootProject).transform,

    //resolvers in ThisBuild += "mvn repo" at "https://raw.githubusercontent.com/colinrgodsey/maven/master",
    resolvers in ThisBuild += Resolver.sonatypeRepo("releases"),
    resolvers in ThisBuild += "sonatype-snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    resolvers in ThisBuild += "apache-snapshots" at "https://repository.apache.org/content/repositories/snapshots",

    scalacOptions in ThisBuild += "-language:dynamics"

    //libraryDependencies in ThisBuild += compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)
  )

  def commonSettings = Seq(
    test in assembly := {},

    //libraryDependencies +=  "com.colingodsey" %%% "logos" % "0.8",
    //libraryDependencies += "com.colingodsey" %%% "logos-akkajs" %   "0.8",
    //libraryDependencies +=  "com.mediamath"   %%% "scala-json" % "1.0",

    libraryDependencies += "org.json4s" %% "json4s-native" % "3.6.7",

    libraryDependencies += "com.lihaoyi"     %%% "utest" % "0.7.4" % "test",

    testFrameworks += new TestFramework("utest.runner.Framework")
  )

  def jvmSettings = Seq(
    libraryDependencies += "com.typesafe.akka" %% "akka-actor" % AkkaV,
    libraryDependencies += "com.typesafe.akka" %% "akka-remote" % AkkaV,
    libraryDependencies += "com.typesafe.akka" %% "akka-http" % "10.1.11",

    libraryDependencies += "org.apache.commons" % "commons-imaging" % "1.0-SNAPSHOT",
    libraryDependencies += "org.apache.commons" % "commons-math3" % "3.6.1",

    libraryDependencies += "org.scream3r"       % "jssc" % "2.8.0"
  )

  def jsSettings = Seq(
    //libraryDependencies += "org.scala-js" %%% "scalajs-tools" % scalaJSVersion,
    //libraryDependencies += "com.colingodsey" %%% "logos-akkajs" % "0.8",

    //libraryDependencies += "eu.unicredit" %%% "akkajsactor" % "0.1.3-SNAPSHOT",

    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "1.0.0"

    //skip in packageJSDependencies := false
  )
}