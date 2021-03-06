/* This file is part of scalambda.
*
* See the NOTICE file distributed with this work for copyright information.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package gnieh.lambda

import scala.io._
import java.io.File
import java.nio.charset.Charset
import scala.util.Properties

import org.apache.commons.io.output.FileWriterWithEncoding

import ast._
import strategy._
import util.Arm._
import util.environment
import analysis.DeBruijn._
import analysis.{ TypeChecker => checker }
import analysis.{ BasicChecks => basic }
import types.ErrorType

import org.kiama.util.{ ParsingREPL, JLineConsole }
import JLineConsole._

object Lambda extends ParsingREPL[Node] with LambdaParsers {

  var strategy: InterpretationStrategy = CallByValueStrategy

  var showReductionSteps = true

  var libPath = new File(Properties.propOrElse("defs.path", ".")).getCanonicalFile

  var aliasesEnabled = true

  var checkTypes = false

  override def setup(args: Array[String]) = {
    // load the libraries passed to the command line
    for (lib <- args) {
      loadLib(lib)
    }
    LambdaConsole.init
    println(
      """\u03BB Interpreter \u00A9 2010 Lucas Satabin
type :help for help and :quit to quit""")
    true
  }

  override def prompt = "\u03BB > "

  def process(n: Node) = n match {
    // enrich environment
    case Assign(name, expr) =>
      environment.bind(name, expr)
      println(name + " added to the environment.")
    // evaluate expression
    case le: LambdaExpr =>
      println("   " + le.toString(!environment.containsExpr(le) && aliasesEnabled))
      if (le -> basic.ok) {
        var ok =
          if (checkTypes) {
            le -> checker.tpe(Map()) match {
              case err: ErrorType =>
                println(err)
                false
              case _ => true
            }
          } else {
            true
          }
        if (ok)
          steps(le)
      }
    // switch strategy
    case NormalOrder => switchTo(NormalOrderStrategy)
    case CallByName => switchTo(CallByNameStrategy)
    case CallByValue => switchTo(CallByValueStrategy)
    // display options
    case ShowSteps => showReductionSteps = true
    case HideSteps => showReductionSteps = false
    case ShowAliases => aliasesEnabled = true
    case HideAliases => aliasesEnabled = false
    // typing enabling/disabling
    case EnableTyping => checkTypes = true
    case DisableTyping => checkTypes = false
    // compute and show the type
    case ShowType(expr) =>
      println(expr -> checker.tpe(Map()))
    case Deriv(expr) =>
      println(checker.derivation(Map(), expr).toLaTeX)
    // show the de Bruijn representation of a term
    case DeBruijnCommand(term) =>
      println(term -> deBruijnTerm(BaseNamingContext))
    // show current environment
    case Env =>
      for ((name, expr) <- environment.definitions)
        println(name + " = " + expr.toString(false))
    case RemoveEnv(names) =>
      for (name <- names)
        environment.unbind(name)
      println(names.mkString("[", ", ", "]") + " removed from the environment")
    // quit
    case Quit => exit
    // help
    case Help =>
      printHelp
    case LoadLib(name) =>
      loadLib(name)
    case SaveLib(name) =>
      saveLib(name)
  }

  def start = line

  /*
   * Loads the library by its name and add the definitions in it to the environment.
   */
  private def loadLib(name: String) {
    val libFile =
      if (name.endsWith(".lbd"))
        new File(libPath, name)
      else
        new File(libPath, name + ".lbd")

    using(Source.fromFile(libFile, "UTF-8")) { source =>
      parseAll(file, source.bufferedReader) match {
        case Success(assigns, _) =>
          for (assign <- assigns) {
            environment.bind(assign.name, assign.expr)
          }
          println("Library " + name + " loaded in the environment")
        case ns: NoSuccess =>
          println("File corrupted: " + ns.msg)
      }
    } {
      case t => println("Unable to load " + name + ".\n" + t.getMessage)
    }
  }

  private def saveLib(name: String) {
    var file = new File(libPath, name + ".lbd")
    // if the file already exists, ask to overwrite it
    val write = if (file.exists) {
      var line: String = null
      while (line != "y" && line != "n")
        line = readLine("A library with this name already exists.\nDo you want to overwrite it? (y/n)")
      line == "y"
    } else {
      true
    }
    if (write) {
      using(new FileWriterWithEncoding(file, Charset.forName("UTF-8"), false)) { fw =>
        fw.write("# saved on " + new java.util.Date + "\n")
        fw.flush
        for ((name, expr) <- environment.definitions) {
          fw.write(name + "=" + expr.toString(false) + ";\n")
          fw.flush
        }
        println("Environment saved to library " + name)
      } {
        case t => println("Unable to save " + name + ".\n" + t.getMessage)
      }
    } else {
      println("Save Aborted")
    }
  }

  private def switchTo(st: InterpretationStrategy) {
    strategy = st
  }

  private def steps(le: LambdaExpr) {
    var current = le
    var last: LambdaExpr = null
    while (true) {
      last = current
      strategy(current) match {
        case Some(e) if last ~= e =>
          println("The term '" + last.toString(false) + "' diverges using the " + strategy.name + " strategy.")
          return
        case Some(e) =>
          current = e
          if (showReductionSteps)
            println(" \u2192 " + current.toString(aliasesEnabled))
        case None =>
          if (!showReductionSteps)
            println(" \u2192 " + current.toString(aliasesEnabled))
          println(" \u21F8")
          return
      }
    }
  }

  private def printHelp {
    println("""Available commands:
 :help               Display this help
 :quit               Quit the \u03BB Interpreter
 :normal-order       Use normal order strategy to reduce the terms (default)
 :show-steps         Show the steps when reducing (enabled by default)
 :hide-steps         Do not show steps when reducing
 :env                Show the current environment 
 :rm <n1> [<n2> ...] Removes the given names from the environment
 :load <name>        Load the definitions from the given library to environment
 :save <name>        Save the current environment to the given library
 :show-aliases       Display alias when an expression is known as an alias (default)
 :hide-aliases       Do not display aliases
 :de-bruijn <expr>   Show the De Bruijn representation of the given lambda term
 :enable-typing      Enable type checking of lambda terms
 :disable-typing     Disable type checking of lambda terms (default)
 :type <expr>        Display the type of the expression
 :derivation <expr>  Creates a LaTeX representation of the typing derivation tree""")
  }

}