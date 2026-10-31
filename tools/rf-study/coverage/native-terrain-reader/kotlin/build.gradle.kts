plugins { kotlin("jvm") version "2.2.20" }
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
dependencies { testImplementation(kotlin("test")) }
