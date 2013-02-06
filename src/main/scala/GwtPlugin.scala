package net.thunderklaus

import sbt._
import sbt.Keys._
import java.io.File
import com.github.siasia.WarPlugin._
import com.github.siasia.WebPlugin._
import com.github.siasia.PluginKeys._

object GwtPlugin extends Plugin {

  lazy val Gwt = config("gwt") extend (Compile)

  // tasks
  val prepareWebapp = TaskKey[Seq[(File, String)]]("prepare-webapp")
  val gwtCompile = TaskKey[Unit]("gwt-compile", "Runs the GWT compiler")
  val gwtDevMode = TaskKey[Unit]("gwt-devmode", "Runs the GWT devmode shell")
  val gwtSuperDevMode = TaskKey[Unit]("gwt-superdevmode", "Runs GWT super devmode server")

  // settings
  val gwtModules = TaskKey[Seq[String]]("gwt-modules")
  val gwtVersion = SettingKey[String]("gwt-version")
  val gwtTemporaryPath = SettingKey[File]("gwt-temporary-path")
  val gwtWebappPath = SettingKey[File]("gwt-webapp-path")
  val gwtDevModeArgs = SettingKey[Seq[String]]("gwt-devmode-args")
  val gwtSuperDevModeArgs = SettingKey[Seq[String]]("gwt-superdevmode-args")
  val gaeSdkPath = SettingKey[Option[String]]("gae-sdk-path")

  // commands
  var gwtModule: Option[String] = None
  val gwtSetModule = Command.single("gwt-set-module") { (state, arg) =>
    Project.runTask(gwtModules, state) map(_._2) match {
      case Some(Value(mods)) => {
        gwtModule = mods.find(_.toLowerCase.contains(arg.toLowerCase))
        gwtModule match {
          case Some(m) => println("gwt-(super)devmode will run: " + m)
          case None => println("No match for '" + arg + "' in " + mods.mkString(", "))
        }
      }
      case _ => None
    }
    state
  }

  lazy val gwtSettings: Seq[Setting[_]] = webSettings ++ gwtOnlySettings

  lazy val gwtOnlySettings: Seq[Setting[_]] = inConfig(Gwt)(Defaults.configSettings) ++ Seq(
    managedClasspath in Gwt <<= (managedClasspath in Compile, update) map {
      (cp, up) => cp ++ Classpaths.managedJars(Provided, Set("src"), up)
    },
    unmanagedClasspath in Gwt <<= (unmanagedClasspath in Compile),
    gwtTemporaryPath <<= (target) { (target) => target / "gwt" },
    gwtWebappPath <<= (target) { (target) => target / "webapp" },
    gwtVersion := "2.3.0",
    gwtDevModeArgs := Seq(),
    gwtSuperDevModeArgs := Seq(),
    gaeSdkPath := None,
    libraryDependencies <++= gwtVersion(gwtVersion => Seq(
      "com.google.gwt" % "gwt-user" % gwtVersion % "provided",
      "com.google.gwt" % "gwt-dev" % gwtVersion % "provided",
      "com.google.gwt" % "gwt-codeserver" % gwtVersion % "provided",
      "javax.validation" % "validation-api" % "1.0.0.GA" % "provided" withSources (),
      "com.google.gwt" % "gwt-servlet" % gwtVersion)),
    gwtModules <<= (javaSource in Compile, resourceDirectory in Compile) map {
      (javaSource, resources) => findGwtModules(javaSource) ++ findGwtModules(resources)
    },

    gwtDevMode <<= (dependencyClasspath in Gwt, thisProject in Gwt,  state in Gwt, javaSource in Compile, javaOptions in Gwt, gwtModules, gwtDevModeArgs, gaeSdkPath, gwtWebappPath, streams) map {
      (dependencyClasspath, thisProject, pstate, javaSource, javaOpts, gwtModules, gwtDevModeArgs, gaeSdkPath, warPath, s) => {
        def gaeFile (path :String*) = gaeSdkPath.map(_ +: path mkString(File.separator))
        val module = gwtModule.getOrElse(gwtModules.headOption.getOrElse(sys.error("Found no .gwt.xml files.")))
        val cp = dependencyClasspath.map(_.data.absolutePath) ++
                 gaeFile("lib", "appengine-tools-api.jar").toList ++
                 Seq(javaSource.absolutePath) ++
                 getDepSources(thisProject.dependencies, pstate)
        val javaArgs = javaOpts ++ (gaeFile("lib", "agent", "appengine-agent.jar") match {
          case None => Nil
          case Some(path) => List("-javaagent:" + path)
        })
        val gwtArgs = List("-war", warPath.absolutePath) ++ gwtDevModeArgs ++ (gaeSdkPath match {
          case None => Nil
          case Some(path) => List(
            "-server", "com.google.appengine.tools.development.gwt.AppEngineLauncher")
        })
        val command = mkGwtCommand(
          cp, javaArgs, "com.google.gwt.dev.DevMode", gwtArgs, List(module))
        s.log.info("Running GWT devmode on: " + module)
        s.log.debug("Running GWT devmode command: " + command)
        command !
      }
    },

    gwtSuperDevMode <<= (classDirectory in Compile, dependencyClasspath in Gwt, thisProject in Gwt,  state in Gwt, javaSource in Compile, javaOptions in Gwt, gwtModules, gwtSuperDevModeArgs, streams) map {
      (classDirectory, dependencyClasspath, thisProject, pstate, javaSource, javaOpts, gwtModules, gwtSuperDevModeArgs, s) => {
        val module = gwtModule.getOrElse(gwtModules.headOption.getOrElse(sys.error("Found no .gwt.xml files.")))
        val cp = Seq(classDirectory.absolutePath) ++
                 dependencyClasspath.map(_.data.absolutePath) ++
                 Seq(javaSource.absolutePath) ++
                 getDepSources(thisProject.dependencies, pstate)
        val gwtArgs = gwtSuperDevModeArgs ++ Seq("-src", javaSource.absolutePath)
        val command = mkGwtCommand(
          cp, javaOpts, "com.google.gwt.dev.codeserver.CodeServer", gwtArgs, List(module))
        s.log.info("Running GWT superdevmode on: " + module)
        s.log.debug("Running GWT superdevmode command: " + command)
        command !
      }
    },

    gwtCompile <<= (classDirectory in Compile, dependencyClasspath in Gwt, thisProject in Gwt, state in Gwt, javaSource in Compile, javaOptions in Gwt, gwtModules, gwtTemporaryPath, streams) map {
      (classDirectory, dependencyClasspath, thisProject, pstate, javaSource, javaOpts, gwtModules, warPath, s) => {
        val cp = Seq(classDirectory.absolutePath) ++
                 dependencyClasspath.map(_.data.absolutePath) ++
                 Seq(javaSource.absolutePath) ++
                 getDepSources(thisProject.dependencies, pstate)
        val gwtArgs = List("-war", warPath.absolutePath)
        val command = mkGwtCommand(cp, javaOpts, "com.google.gwt.dev.Compiler", gwtArgs, gwtModules)
        s.log.info("Compiling GWT modules: " + gwtModules.mkString(", "))
        s.log.debug("Running GWT compiler command: " + command)
        command !
      }
    },
    webappResources in Compile <+= (gwtTemporaryPath) { (t: File) => t },

    // make package-war depend on gwt-compile
    packageWar in Compile <<= (packageWar in Compile).dependsOn(gwtCompile in Compile),

    // create a duplicate of package-war and make gwt-devmode depend on that; we don't want
    // gwt-devmode to depend on the real package-war because that depends on gwt-compile and we
    // don't want to have to run a gwt-compile every time we run gwt-devmode, we just want to do
    // everything else that package-war does
    webappResources <<= (sourceDirectory in Compile)(sd => Seq(sd / "webapp")),
    webappResources <++= Defaults.inDependencies(webappResources, ref => Nil, false) apply { _.flatten },
    warPostProcess := { () => () },
    prepareWebapp in Gwt <<= packageWarTask(DefaultClasspathConf),
    gwtDevMode <<= gwtDevMode.dependsOn(compile in Compile, prepareWebapp in Gwt),

    commands ++= Seq(gwtSetModule)
  )

  def getDepSources(deps : Seq[ClasspathDep[ProjectRef]], state : State) : Set[String] = {
    var sources = Set.empty[String]
    val structure = Project.extract(state).structure
    def get[A] = setting[A](structure)_
    deps.foreach{
    dep=>
      sources +=  (get(dep.project, Keys.javaSource, Compile).get.toString)
      sources ++= getDepSources(Project.getProject(dep.project, structure).get.dependencies, state)
    }
    sources
  }

  def setting[T](structure: Load.BuildStructure)(ref: ProjectRef, key: SettingKey[T], configuration: Configuration): Option[T] = key in (ref, configuration) get structure.data

  private def mkGwtCommand(cp: Seq[String], javaArgs: Seq[String], clazz: String,
                           gwtArgs: Seq[String], modules: Seq[String]) =
    (List("java", "-cp", cp.mkString(File.pathSeparator)) ++ javaArgs ++
     List(clazz) ++ gwtArgs ++ modules).mkString(" ")

  private def findGwtModules(srcRoot: File): Seq[String] = {
    import Path.relativeTo
    val files = (srcRoot ** "*.gwt.xml").get
    val relativeStrings = files.flatMap(_ x relativeTo(srcRoot)).map(_._2)
    relativeStrings.map(_.dropRight(".gwt.xml".length).replace(File.separator, "."))
  }
}
