@ECHO OFF
SET APP_HOME=%~dp0
SET CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
IF DEFINED JAVA_HOME (SET JAVACMD=%JAVA_HOME%\bin\java) ELSE (SET JAVACMD=java)
"%JAVACMD%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
