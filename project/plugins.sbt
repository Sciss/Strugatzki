resolvers += Resolver.url( "sbt-plugin-releases", url( "http://scalasbt.artifactoryonline.com/scalasbt/sbt-plugin-releases" ))( Resolver.ivyStylePatterns )

resolvers ++= Seq(
  "less is" at "http://repo.lessis.me",
  "coda" at "http://repo.codahale.com"
)

addSbtPlugin( "me.lessis" % "ls-sbt" % "0.1.0" )

addSbtPlugin( "com.jsuereth" % "xsbt-gpg-plugin" % "0.5" )

