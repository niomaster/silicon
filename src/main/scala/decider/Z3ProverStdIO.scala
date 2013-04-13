package semper
package silicon
package decider

import java.io.{PrintWriter, BufferedWriter, File, InputStreamReader, BufferedReader, OutputStreamWriter}
import scala.collection.mutable.{HashMap, Stack}
import com.weiglewilczek.slf4s.Logging
import interfaces.decider.{Prover, Sat, Unsat, Unknown}
import state.terms._
import reporting.{Bookkeeper, Z3InteractionFailed}

/* TODO: Pass a logger, don't open an own file to log to. */
class Z3ProverStdIO(z3path: String, logpath: String, bookkeeper: Bookkeeper) extends Prover with Logging {
  val termConverter = new TermToSMTLib2Converter()
	import termConverter._

	private var scopeCounter = 0
	private var scopeLabels = new HashMap[String, Stack[Int]]()

	private var isLoggingCommentsEnabled: Boolean = true

  private val logfile =
		if (logpath != null) common.io.PrintWriter(new File(logpath))
		else null

  /* TODO: Get Z3's version and log a warning if the version doesn't match the expected version. */
  private val z3 = {
    logger.info(s"Starting Z3 at $z3path")

    val builder = new ProcessBuilder(z3path, "-smt2", "-in")
		builder.redirectErrorStream(true)

    val process = builder.start()

		Runtime.getRuntime().addShutdownHook(new Thread {
			override def run() {
				process.destroy()
			}
		})

    process
  }

  private val input =
		new BufferedReader(new InputStreamReader(z3.getInputStream()))

  private val output =
		new PrintWriter(
			new BufferedWriter(new OutputStreamWriter(z3.getOutputStream())), true)

  def z3Version() = {
    val versionPattern = """\(?\s*:version\s+"(.*?)"\)?""".r
    var line = ""

    writeLine("(get-info :version)")

    line = input.readLine()
    logComment(line)

    line match {
      case versionPattern(v) => v
      case _ => throw new Z3InteractionFailed(s"Unexpected output of Z3 while getting version: $line")
    }
  }

  def stop() {
    this.synchronized {
      z3.destroy()
    }
  }

	def	push(label: String) {
		val stack =
			scopeLabels.getOrElse(
				label,
				{val s = new Stack[Int](); scopeLabels(label) = s; s})

		stack.push(scopeCounter)
		push()
	}

	def	pop(label: String) {
		val stack = scopeLabels(label)
		val n = scopeCounter - stack.head
		stack.pop()
		pop(n)
	}

	def push(n: Int = 1) {
		scopeCounter += n
		val cmd = (if (n == 1) "(push)" else "(push " + n + ")") + " ; " + scopeCounter
    writeLine(cmd)
		readSuccess
	}

  def pop(n: Int = 1) {
		val cmd = (if (n == 1) "(pop)" else "(pop " + n + ")") + " ; " + scopeCounter
		scopeCounter -= n
    writeLine(cmd)
		readSuccess
  }

	def write(content: String) {
    writeLine(content)
		readSuccess
	}

  def assume(term: Term) = assume(convert(term))

  def assume(term: String) {
		bookkeeper.assumptionCounter += 1

		writeLine("(assert " + term + ")")
		readSuccess
  }

  def assert(goal: Term) = assert(convert(goal))

  def assert(goal: String) = {
    bookkeeper.assertionCounter += 1

		push()
		writeLine("(assert (not " + goal + "))")
		readSuccess
		writeLine("(check-sat)")
		val r = readUnsat
		pop()

		r
  }

	def check() = {
		writeLine("(check-sat)")

		readLine match {
			case "sat" => Sat
			case "unsat" => Unsat
			case "unknown" => Unknown
		}
	}

  def getStatistics(): Map[String, String]= {
    var repeat = true
    var line = ""
    var stats = scala.collection.immutable.SortedMap[String, String]()
    val entryPattern = """\(?\s*:([A-za-z\-]+)\s+((?:\d+\.)?\d+)\)?""".r

    writeLine("(get-info :all-statistics)")

    do {
      line = input.readLine()
      logComment(line)

      /* Check that the first line starts with "(:". */
      if (line.isEmpty && !line.startsWith("(:"))
        throw new Z3InteractionFailed(s"Unexpected output of Z3 while reading statistics: $line")

      line match {
        case entryPattern(entryName, entryNumber) =>
          stats = stats + (entryName -> entryNumber)
        case _ =>
      }

      repeat = !line.endsWith(")")
    } while (repeat)
    stats
  }

	def enableLoggingComments(enabled: Boolean) = isLoggingCommentsEnabled = enabled

	def logComment(str: String) = if (isLoggingCommentsEnabled) log("; " + str)

	private def freshId(prefix: String) = prefix + "@" + counter.next()

  /* TODO: Could we decouple fresh from Var, e.g. return the used freshId, without
   *       losing conciseness at call-site?
   */
  def fresh(id: String, sort: Sort) = {
    val v = Var(freshId(id), sort)
    val decl = "(declare-const %s %s)".format(sanitiseIdentifier(v.id), convert(v.sort))
    write(decl)

    v
  }

  def declareSymbol(id: String, argSorts: Seq[Sort], sort: Sort) {
    val str = "(declare-fun %s (%s) %s)".format(sanitiseIdentifier(id),
      argSorts.map(convert).mkString(" "),
      convert(sort))

    write(str)
  }

  def declareSort(sort: Sort) {
    val str = "(declare-sort %s)".format(convert(sort))

    write(str)
  }

  def resetAssertionCounter() { bookkeeper.assertionCounter = 0 }
  def resetAssumptionCounter() { bookkeeper.assumptionCounter = 0 }

  def resetCounters() {
    resetAssertionCounter()
    resetAssumptionCounter()
  }

	/* TODO: Handle multi-line output, e.g. multiple error messages. */

	private def readSuccess() {
		val answer = readLine()

		if (answer != "success")
      throw new Z3InteractionFailed(s"Unexpected output of Z3. Expected 'success' but found: $answer")
	}

	private def readUnsat(): Boolean = readLine() match {
		case "unsat" => true
		case "sat" => false
		case "unknown" => false

    case result =>
      throw new Z3InteractionFailed(s"Unexpected output of Z3 while trying to refute an assertion: $result")
	}

  private def readLine(): String = {
		var repeat = true
		var result = ""

		while (repeat) {
			result = input.readLine();
			if (result.toLowerCase != "success") logComment(result)

			repeat = result.startsWith("WARNING")
		}

    result
  }

	private def log(str: String) {
		if (logfile != null) logfile.println(str);
	}

  private def writeLine(out: String) = {
    log(out);
    output.println(out);
  }

	private object counter {
		private var value = 0

		def next() = {
			value = value + 1
			value
		}
	}
}
