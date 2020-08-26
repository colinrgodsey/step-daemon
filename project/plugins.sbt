resolvers += "spray repo" at "https://repo.spray.io"

resolvers += "Typesafe repository" at "https://repo.typesafe.com/typesafe/releases/"

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.0.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.0.0")

//addSbtPlugin("com.lihaoyi" % "workbench" % "0.4.1")

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.10")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.0")