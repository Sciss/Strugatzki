lazy val commonSettings = Seq(
  name               := "Strugatzki",
  version            := "2.19.0",
  organization       := "de.sciss",
  scalaVersion       := "2.12.8",
  crossScalaVersions := Seq("2.13.0", "2.12.8", "2.11.12"),
  description        := "Algorithms for extracting audio features and matching audio file similarities",
  homepage           := Some(url(s"https://git.iem.at/sciss/${name.value}")),
  licenses           := Seq("LGPL v2.1+" -> url("http://www.gnu.org/licenses/lgpl-2.1.txt"))
)

lazy val deps = new {
  val main = new {
    val fileUtil      = "1.1.3"
    val palette       = "1.0.0"
    val scalaCollider = "1.28.4"
    val scopt         = "3.7.1"
    val span          = "1.4.2"
  }
  val test = new {
    val scalaTest     = "3.0.8"
  }
}

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .settings(assemblySettings)
  .settings(publishSettings)
  .enablePlugins(BuildInfoPlugin)
  .settings(
    libraryDependencies ++= Seq(
      "de.sciss"          %% "scalacollider"    % deps.main.scalaCollider,   // for the feature ugens
      "de.sciss"          %% "span"             % deps.main.span,            // representation of time spans
      "de.sciss"          %  "intensitypalette" % deps.main.palette,         // color scheme for self similarity
      "de.sciss"          %% "fileutil"         % deps.main.fileUtil,        // easy path compositions
      "com.github.scopt"  %% "scopt"            % deps.main.scopt,           // parsing command line options
      "org.scalatest"     %% "scalatest"        % deps.test.scalaTest % Test
    ),
    scalacOptions ++= Seq("-deprecation", "-unchecked", "-feature", "-encoding", "utf8", "-Xsource:2.13", "-Xlint"),
    // build info
    buildInfoKeys := Seq(name, organization, version, scalaVersion, description,
      BuildInfoKey.map(homepage) { case (k, opt)             => k -> opt.get },
      BuildInfoKey.map(licenses) { case (_, Seq( (lic, _) )) => "license" -> lic }
    ),
    buildInfoPackage := "de.sciss.strugatzki"
  )

lazy val publishSettings = Seq(
  publishMavenStyle := true,
  publishTo := {
    Some(if (isSnapshot.value)
      "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots"
    else
      "Sonatype Releases" at "https://oss.sonatype.org/service/local/staging/deploy/maven2"
    )
  },
  publishArtifact in Test := false,
  pomIncludeRepository := { _ => false },
  pomExtra := { val n = name.value
    <scm>
      <url>git@git.iem.at:sciss/{n}.git</url>
      <connection>scm:git:git@git.iem.at:sciss/{n}.git</connection>
    </scm>
      <developers>
        <developer>
          <id>sciss</id>
          <name>Hanns Holger Rutz</name>
          <url>http://www.sciss.de</url>
        </developer>
      </developers>
  }
)

lazy val assemblySettings = Seq(
  test            in assembly := {},
  target          in assembly := baseDirectory.value,
  assemblyJarName in assembly := s"${name.value}.jar"
)
