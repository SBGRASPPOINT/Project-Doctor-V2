plugins {
    java
    id("org.springframework.boot") version "4.1.2"          // Spring Boot 4.x (4.1.2, Oct 2026)
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.vobiz"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)        // Java 25 LTS
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot 4 modular starters (spring-boot-starter-web was renamed to -webmvc in Boot 4)
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")       // HikariCP + DataSource for PostgreSQL
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-log4j2")     // Log4j2 instead of Logback

    runtimeOnly("org.postgresql:postgresql")                                 // PostgreSQL JDBC driver (version from Boot BOM)

    compileOnly("org.projectlombok:lombok")                                  // Lombok 1.18.44 (JDK 25 support)
    annotationProcessor("org.projectlombok:lombok")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
}

// We log through Log4j2, so drop Logback and the log4j->slf4j bridge everywhere.
configurations.all {
    exclude(group = "ch.qos.logback")
    exclude(group = "org.apache.logging.log4j", module = "log4j-to-slf4j")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.bootJar {
    archiveFileName.set("vobiz-call-masking.jar")
}
