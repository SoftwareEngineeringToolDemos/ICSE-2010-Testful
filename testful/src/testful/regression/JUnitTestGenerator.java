package testful.regression;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import testful.ConfigProject;
import testful.IConfigProject;
import testful.IConfigRunner;
import testful.TestFul;
import testful.coverage.CoverageInformation;
import testful.coverage.TestSizeInformation;
import testful.coverage.TrackerDatum;
import testful.model.AssignPrimitive;
import testful.model.Clazz;
import testful.model.CreateObject;
import testful.model.Invoke;
import testful.model.Operation;
import testful.model.OperationResult;
import testful.model.PrimitiveClazz;
import testful.model.Reference;
import testful.model.Test;
import testful.model.TestCoverage;
import testful.model.TestExecutionManager;
import testful.model.TestReader;
import testful.runner.ClassFinder;
import testful.runner.ClassFinderCaching;
import testful.runner.ClassFinderImpl;
import testful.runner.RunnerPool;
import testful.utils.ElementManager;
import testful.utils.ElementWithKey;

/**
 * Given a test, generates jUnit3 test cases & test suite
 *
 * @author matteo
 */
public class JUnitTestGenerator extends TestReader {
	private static final Logger logger = Logger.getLogger("testful.regression");

	/** maximum number of operation per jUnit test */
	private static final int MAX_TEST_LEN = 1000;

	private final File destDir;
	private final TestSuite suite = new TestSuite();
	public JUnitTestGenerator(File destDir) {
		this.destDir = destDir;
	}

	@Override
	public void read(String name, Test test) {
		String className = test.getCluster().getCut().getClassName();
		TestCase testCase = suite.get(className);

		// write the binary file
		File dir = new File(destDir, testCase.getPackageName().replace('.', File.separatorChar));
		dir.mkdirs();

		File testFile = new File(dir, (testCase.getClassName() + "_" + name).replace('-', '_').replace(' ', '_') + ".ser.gz");

		try {
			test.write(new GZIPOutputStream(new FileOutputStream(testFile)));
		} catch (IOException e) {
			logger.log(Level.WARNING, "Cannot write the test to file: " + e.getLocalizedMessage(), e);
		}

		// add to a jUnit test
		testCase.add(testFile.getPath(), test);
	}

	public void writeSuite() {
		suite.write();
	}

	@Override
	public Logger getLogger() {
		return logger;
	}

	/**
	 * Contains tests for different classes.
	 * @author matteo
	 */
	private class TestSuite extends ElementManager<String, TestCase>{
		private static final long serialVersionUID = -209417855781416430L;

		@Override
		public TestCase get(String className) {
			TestCase test = super.get(className);
			if(test == null) {
				test = new TestCase(className);
				put(test);
			}
			return test;
		}

		public void write() {
			for (TestCase test : this) {
				List<String> tests = test.write();

				// if there is only 1 test, skip the creation of the suite!
				if(tests.size() > 1) {
					try {
						String suiteName = "AllTests_" + test.getClassName();

						File testFile = new File(destDir, test.getPackageName().replace('.', File.separatorChar) + File.separatorChar + suiteName + ".java");
						PrintWriter wr = new PrintWriter(testFile);

						if(!test.getPackageName().isEmpty()) {
							wr.println("package " + test.getPackageName() + ";");
							wr.println();
						}

						wr.println("import junit.framework.*;");
						wr.println("import junit.textui.*;");
						wr.println();
						wr.println("public class " + suiteName + " {");
						wr.println();
						wr.println("\tpublic static void main(String[] args) {");
						wr.println("\t\tTestRunner runner = new TestRunner();");
						wr.println("\t\tTestResult result = runner.doRun(suite(), false);");
						wr.println();
						wr.println("\t\tif (! result.wasSuccessful())");
						wr.println("\t\t\tSystem.exit(1);");
						wr.println("\t}");

						wr.println("\tpublic static junit.framework.Test suite() {");
						wr.println("\t\tjunit.framework.TestSuite suite = new junit.framework.TestSuite(\"Test generated by testFul\");");
						wr.println();

						for(String testName : tests)
							wr.println("\t\tsuite.addTestSuite(" + testName + ".class);");

						wr.println();
						wr.println("\t\treturn suite;");
						wr.println("\t}");
						wr.println("}");
						wr.close();

						logger.info("Test suite " + suiteName + " saved");

					} catch(IOException e) {
						logger.log(Level.WARNING, "Cannot write the test suite: " + e.getMessage(), e);
					}
				}
			}
		}
	}

	/**
	 * Contains tests for a single class
	 * @author matteo
	 */
	private class TestCase implements ElementWithKey<String> {

		/** full qualified class name */
		private final String fullQualifiedClassName;

		/** the package of the class */
		private final String packageName;

		/** the (simple) class name, without package */
		private final String className;

		private final Map<String, Test> tests = new HashMap<String, Test>();

		public TestCase(String className) {
			fullQualifiedClassName = className;

			{
				StringBuilder pkgBuilder =  new StringBuilder();
				String[] parts = className.split("\\.");

				this.className = parts[parts.length - 1];

				for(int i = 0; i < parts.length-1; i++) {
					if(i > 0) pkgBuilder.append(".");
					pkgBuilder.append(parts[i]);
				}

				packageName = pkgBuilder.toString();
			}
		}

		public String getPackageName() {
			return packageName;
		}

		public String getClassName() {
			return className;
		}

		private String getTestName(Integer idx) {
			String testName = className.replace('-', '_');
			testName = testName.replace(' ', '_');
			if(idx != null) testName = testName + "_" + idx;
			return testName + "_TestCase";
		}

		public void add(String name, Test t) {
			tests.put(name, t);
		}

		@Override
		public String getKey() {
			return fullQualifiedClassName;
		}

		public List<String> write() {
			final List<String> fileNames = new ArrayList<String>();

			final boolean singleTest = isSingleTest(tests.values());

			/** the progressive number of a jUnit TestCase */
			int currentJUnitTest = 0;

			/** progressive number of each test method, within a single jUnit TestCase */
			int currentTestMethod = 0;

			int testLength = 0;
			PrintWriter writer = null;
			for (Entry<String, Test> e : tests.entrySet()) {

				try {
					testLength += e.getValue().getTest().length;
					if(writer == null || testLength > MAX_TEST_LEN) {

						if(writer != null) writeFooterAndClose(writer);

						String testName = getTestName(singleTest?null:currentJUnitTest++);

						File dir = new File(destDir, packageName.replace('.', File.separatorChar));
						dir.mkdirs();

						File testFile = new File(dir, testName + ".java");
						writer = new PrintWriter(testFile);

						logger.info("Creating test " + testName);
						writeHeader(writer, testName);
						fileNames.add((packageName.isEmpty()? "" : packageName + ".") + testName);
						currentTestMethod = 0;
						testLength = e.getValue().getTest().length;
					}

					writeTest(e.getKey(), e.getValue(), writer, ++currentTestMethod);

				} catch (FileNotFoundException exc) {
					logger.log(Level.WARNING, "Cannot open the file: " + exc.getMessage(), exc);
				}
			}

			if(writer != null) writeFooterAndClose(writer);

			return fileNames;
		}


		private void writeTest(String name, Test test, PrintWriter out, int testNumber) {

			out.println();

			out.println("\t// Binary test: " + name);

			if(test instanceof TestCoverage) {
				for (CoverageInformation cov : ((TestCoverage)test).getCoverage())
					if(!(cov instanceof TestSizeInformation))
						out.println("\t// " + cov.getName() + ": " + cov.getQuality());
			}
			out.println("\tpublic void testFul" + testNumber + "() throws Exception {");
			out.println();

			// create variables
			out.println("\t\tObject tmp = null;");

			{	// group references by type
				Map<String, List<String>> map = new HashMap<String, List<String>>();
				for(Reference ref : test.getReferenceFactory().getReferences()) {
					List<String> vars = map.get(ref.getClazz().getClassName());
					if(vars == null) {
						vars = new ArrayList<String>();
						map.put(ref.getClazz().getClassName(), vars);
					}
					vars.add(ref.toString());
				}
				// create variable lines: "Type var1 = null, var2 = null;"
				for (String type : map.keySet()) {
					out.print("\t\t" + type);

					boolean first = true;
					for (String var : map.get(type)) {
						if(first) first = false;
						else out.print(",");

						out.print(" " + var + " = null");
					}
					out.println(";");
				}
			}

			out.println();
			for(Operation op : test.getTest()) {
				OperationResult status = (OperationResult) op.getInfo(OperationResult.KEY);

				if(status == null) {
					if(op instanceof Invoke || op instanceof CreateObject) {
						out.println("\t\ttry {");
						out.println("\t\t\t" + op + ";");
						out.println("\t\t} catch(Throwable e) {");
						out.println("\t\t\te.printStackTrace();");
						out.println("\t\t}");
					} else {
						out.println("\t\t" + op + ";");
					}

				} else {
					switch(status.getStatus()) {
					case SUCCESSFUL:
						if(op instanceof Invoke) {

							OperationResult result = (OperationResult) op.getInfo(OperationResult.KEY);
							if(result != null) {
								Reference target = ((Invoke) op).getTarget();;
								Clazz retType = ((Invoke)op).getMethod().getReturnType();

								Invoke inv = new Invoke(null, ((Invoke) op).getThis(), ((Invoke) op).getMethod(), ((Invoke) op).getParams());
								out.println("\t\ttmp = " + inv + ";");

								// Put the value also in the original target
								if(target != null) {
									final String cast;
									if(target.getClazz() instanceof PrimitiveClazz) {
										cast = "(" + ((PrimitiveClazz) target.getClazz()).getReferenceClazz().getClassName() + ")";
									} else {
										cast = "(" + target.getClazz().getClassName() + ")";
									}

									out.println("\t\t" + target.toString() + " = " + cast + " tmp;");
								}

								// check the result (tmp's value)
								if(retType instanceof PrimitiveClazz) {
									switch(((PrimitiveClazz) retType).getType()) {
									case BooleanClass:
									case BooleanType:
										out.println("\t\tassertEquals((boolean) " + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (boolean) (java.lang.Boolean) tmp);");
										break;
									case ByteClass:
									case ByteType:
										out.println("\t\tassertEquals((byte)" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (byte) (java.lang.Byte) tmp);");
										break;
									case CharacterClass:
									case CharacterType:
										out.println("\t\tassertEquals((char)" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (char) (java.lang.Character) tmp);");
										break;
									case ShortClass:
									case ShortType:
										out.println("\t\tassertEquals((short)" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (short) (java.lang.Short) tmp);");
										break;
									case IntegerClass:
									case IntegerType:
										out.println("\t\tassertEquals((int)" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (int) (java.lang.Integer) tmp);");
										break;
									case LongClass:
									case LongType:
										out.println("\t\tassertEquals((long)" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (long) (java.lang.Long) tmp);");
										break;
									case FloatClass:
									case FloatType:
										out.println("\t\tassertEquals((float)" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (float) (java.lang.Float) tmp, 0.001f);");
										break;
									case DoubleClass:
									case DoubleType:
										out.println("\t\tassertEquals((double)" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (double) (java.lang.Double) tmp, 0.001);");
										break;
									}
								} else if(retType.getClassName().equals(String.class.getCanonicalName())) {
									out.println("\t\tassertEquals(" + AssignPrimitive.getValueString(result.getResult().getObject()) + ", (java.lang.String) tmp);");
								} else {
									logger.warning("Unusable return status: " + retType.getClassName());
								}

								out.println();

								break;
							}
						}

						out.println("\t\t" + op + ";");
						break;

					case EXCEPTIONAL:
						out.println("\t\ttry {");
						out.println("\t\t\t" + op + ";");
						out.println("\t\t\tfail(\"Expecting a " + status.getException() + "\");");
						out.println("\t\t} catch(" + status.getException().getClass().getCanonicalName() + " e) {");
						out.println("\t\t\tassertEquals(\"" + status.getException().getMessage() + "\", e.getMessage());");
						out.println("\t\t}");
						break;

					case POSTCONDITION_ERROR:
						out.println("\t\t" + op + "; //TODO: this was a faulty invocation!");
						break;

					case NOT_EXECUTED:
						//skip: the operation has not been executed!
						break;
					case PRECONDITION_ERROR:
						//skip: the operation has not been executed!
						break;
					}
				}
			}

			out.println("\t}");
		}

		private void writeHeader(PrintWriter out, String testName) {
			if(!packageName.isEmpty()) {
				out.println("package " + packageName + ";");
				out.println();
			}

			out.println("/** Test Generated by TestFul */");
			out.println("public class " + testName + " extends junit.framework.TestCase {");
		}

		private void writeFooterAndClose(PrintWriter writer) {
			writer.println("}");
			writer.println();
			writer.close();
		}

		/**
		 * Checks if the optimal tests fits in a single test
		 * @param tests the optimal tests
		 * @return true if the sum of all the operations are less than the maximum length of a test
		 */
		private boolean isSingleTest(Collection<? extends Test> tests) {
			int tot = 0;
			for (Test t : tests) {
				tot += t.getTest().length;
				if(tot > MAX_TEST_LEN)
					return false;
			}

			return true;
		}

		@Override
		public TestCase clone() throws CloneNotSupportedException {
			throw new CloneNotSupportedException("It is impossible to clone test cases");
		}
	}

	// ------------------------------- main -----------------------------

	public static void main(String[] args) {

		Config config = new Config();
		TestFul.parseCommandLine(config, args, JUnitTestGenerator.class, "JUnit test generator");

		if(!config.isQuiet())
			TestFul.printHeader("JUnit test generator");

		TestFul.setupLogging(config);
		RunnerPool.getRunnerPool().config(config);

		ClassFinderCaching finder = null;
		try {
			finder = new ClassFinderCaching(new ClassFinderImpl(config.getDirInstrumented(), config.getDirContracts(), config.getDirCompiled()));
		} catch (RemoteException e) {
			// never happens
		}

		JUnitTestGenerator gen = new JUnitTestGenerator(config.getDirGeneratedTests());

		gen.process(getOpStatus(finder, simplify(finder, config.tests)));

		gen.writeSuite();

		System.exit(0);
	}

	private static class Config extends ConfigProject implements IConfigProject.Args4j, IConfigRunner.Args4j {

		@Option(required = false, name = "-dirTests", usage = "Specify the directory in which generated tests will be put.")
		private File dirGeneratedTests;

		@Argument
		private List<String> tests = new ArrayList<String>();

		private List<String> remote = new ArrayList<String>();

		private boolean localEvaluation = true;

		@Override
		public List<String> getRemote() {
			return remote;
		}

		@Override
		public void addRemote(String remote) {
			this.remote.add(remote);
		}

		@Override
		public boolean isLocalEvaluation() {
			return localEvaluation;
		}

		@Override
		public void disableLocalEvaluation(boolean disableLocalEvaluation) {
			localEvaluation = !disableLocalEvaluation;
		}

		public File getDirGeneratedTests() {
			if(!dirGeneratedTests.isAbsolute()) dirGeneratedTests = new File(getDirBase(), dirGeneratedTests.getPath()).getAbsoluteFile();
			return dirGeneratedTests;
		}
	}

	private static List<TestCoverage> getOpStatus(ClassFinder finder, Iterable<TestCoverage> simplify) {
		List<TestCoverage> ret = new ArrayList<TestCoverage>();

		for (TestCoverage test : simplify) {
			try {
				Operation[] op = TestExecutionManager.getOpStatus(finder, test);
				ret.add(new TestCoverage(new Test(test.getCluster(), test.getReferenceFactory(), op), test.getCoverage()));
			} catch (Exception e) {
				logger.log(Level.WARNING, "Cannot execute a test: " + e.getLocalizedMessage(), e);
				ret.add(test);
			}
		}

		return ret;
	}

	private static Collection<TestCoverage> simplify(ClassFinder finder, List<String> tests) {
		final TestSuiteReducer reducer = new TestSuiteReducer(finder, new TrackerDatum[0]);
		new TestReader() {

			@Override
			protected Logger getLogger() {
				return logger;
			}

			@Override
			protected void read(String fileName, Test test) {
				reducer.process(test);
			}

		}.read(tests);

		return reducer.getOutput();
	}
}
