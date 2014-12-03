package autoderiv;

import java.io.PrintStream;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.Status;

public class Debug {
	public static boolean printInfo = true;
	private static final String PREINFO = "i.";

	public static boolean printWarn = true;
	private static final String PREWARN = "!.";

	public static boolean printLog = true;
	public static ILog log;


	/**print an info text line (clickable from eclipse console) */
	public static void info(String str) {
		if (printInfo){
			common(PREINFO + str, System.out, Status.INFO);
		}
	}

	/**print a warn text line (clickable from eclipse console) */
	public static void warn(String str) {
		if (printWarn) {
			common(PREWARN + str, System.err, Status.WARNING);
		}
	}
	
	private static void common(String str, PrintStream flux, int status_level) {
		StackTraceElement s = Thread.currentThread().getStackTrace()[3];
		String msg_ = String.format("%s:\t%s @ %s.%s(%s:%s)%n",Tools.getms(), str, s.getClassName(), s.getMethodName(), s.getFileName(), s.getLineNumber());
		flux.print(msg_);
	}
	
}