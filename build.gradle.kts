plugins {
    base
    id("io.ia.sdk.modl") version("0.1.1")
}

allprojects {
    version = "1.0.0-SNAPSHOT"
    group = "com.ignite"
}

ignitionModule {
    fileName.set("Ignite")

    name.set("Ignite")
    id.set("com.ignite.module")
    moduleVersion.set("${project.version}")
    moduleDescription.set("API gateway and static site host for Ignition 8.3+")

    requiredIgnitionVersion.set("8.3.0")

    // MIT-licensed, unsigned: must agree with IgniteGatewayHook.isFreeModule()
    freeModule.set(true)

    license.set("license.html")

    // No module dependencies (no Perspective)

    projectScopes.putAll(
        mapOf(
            ":gateway" to "G",
            ":designer" to "D",
            ":common" to "GD"
        )
    )

    hooks.putAll(
        mapOf(
            "com.ignite.gateway.IgniteGatewayHook" to "G",
            "com.ignite.designer.IgniteDesignerHook" to "D"
        )
    )

    skipModlSigning.set(true)
}

val deepClean by tasks.registering {
    dependsOn(allprojects.map { "${it.path}:clean" })
    description = "Executes clean tasks and removes all build caches"
    doLast {
        delete(file(".gradle"))
        delete(file("build"))
    }
}
