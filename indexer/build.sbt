version := "0.1.0-SNAPSHOT"

scalaVersion := "2.13.10"
name         := "metarank-esci-import"

lazy val circeVersion = "0.14.5"

libraryDependencies ++= Seq(
  "co.fs2"                    %% "fs2-core"           % "3.6.1",
  "co.fs2"                    %% "fs2-io"             % "3.6.1",
  "co.elastic.clients"         % "elasticsearch-java" % "8.7.1",
  "com.fasterxml.jackson.core" % "jackson-databind"   % "2.12.3",
  "io.circe"                  %% "circe-core"         % circeVersion,
  "io.circe"                  %% "circe-generic"      % circeVersion,
  "io.circe"                  %% "circe-parser"       % circeVersion,
  "com.github.luben"           % "zstd-jni"           % "1.5.5-2",
  "com.opencsv"                % "opencsv"            % "5.7.1"
)
