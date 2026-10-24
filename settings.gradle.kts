pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "UniAttendance"
include(":core", ":licensekit", ":student", ":doctor", ":admin", ":devconsole")
