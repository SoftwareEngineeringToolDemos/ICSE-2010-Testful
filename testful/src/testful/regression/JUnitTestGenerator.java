/*
 * TestFul - http://code.google.com/p/testful/
 * Copyright (C) 2010  Matteo Miraz
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package testful.regression;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Serializable;
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
import testful.TestFul;
import testful.coverage.CoverageInformation;
import testful.model.Clazz;
import testful.model.CreateObject;
import testful.model.Invoke;
import testful.model.Operation;
import testful.model.OperationResult;
import testful.model.OperationResult.Value;
import testful.model.PrimitiveClazz;
import testful.model.Reference;
import testful.model.Test;
import testful.model.TestCoverage;
import testful.model.TestReader;
import testful.model.transformation.ReferenceSorter;
import testful.model.transformation.RemoveUselessDefs;
import testful.model.transformation.Reorganizer;
import testful.model.transformation.SimplifierStatic;
import testful.model.transformation.SingleStaticAssignment;
import testful.model.transformation.TestTransformation;
import testful.model.transformation.TestTransformationPipeline;
import testful.runner.ClassType;
import testful.runner.DataFinderCaching;
import testful.runner.DataFinderImpl;
import testful.runner.RemoteClassLoader;
import testful.utils.ElementManager;
import testful.utils.ElementWithKey;
import testful.utils.JavaUtils;

/**
 * Given a test, generates jUnit3 test cases & test suite
 *
 * @author matteo
 */
public class JUnitTestGenerator extends TestReader {

	private static final boolean createAssertions = TestFul.getProperty(TestFul.PROPERTY_JUNIT_ASSERTIONS, true);
	private static final Logger logger = Logger.getLogger("testful.regression");

	private final static TestTransformation transformation = new TestTransformationPipeline(
			SimplifierStatic.singleton,
			SingleStaticAssignment.singleton,
			RemoveUselessDefs.singleton,
			Reorganizer.singleton,
			ReferenceSorter.singleton
	);

	/** maximum number of operation for each jUnit test */
	private static final int MAX_TEST_LEN = 2000;

	private final File destDir;
	private final boolean saveBinaryTests;
	private final RemoteClassLoader classLoader;
	private final TestSuite suite = new TestSuite();
	public JUnitTestGenerator(File destDir, RemoteClassLoader classLoader, boolean saveBinaryTests) {
		this.destDir = destDir;
		this.classLoader = classLoader;
		this.saveBinaryTests = saveBinaryTests;
	}

	@Override
	public void read(String name, Test test) {
		final String className = test.getCluster().getCut().getClassName();
		final TestCase testCase = suite.get(className);

		// write the binary file
		if(saveBinaryTests) {
			File dir = new File(destDir, testCase.getPackageName().replace('.', File.separatorChar));
			dir.mkdirs();

			try {
				File testFile = new File(dir, (testCase.getClassName() + "_" + name).replace('-', '_').replace(' ', '_') + ".ser.gz");
				test.write(new GZIPOutputStream(new FileOutputStream(testFile)));
				name = testFile.getPath();
			} catch (IOException e) {
				logger.log(Level.WARNING, "Cannot write the test to file: " + e.getLocalizedMessage(), e);
			}
		}

		if(TestFul.getProperty(TestFul.PROPERTY_JUNIT_SIMPLIFY, true))
			test = transformation.perform(test);

		// add to a jUnit test
		testCase.add(name, test);
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

						// main method has been removed. Use junit's TestRunner:
						// java -cp junit.jar junit.textui.TestRunner package.TestSuiteName
						// java -cp junit.jar junit.textui.TestRunner package.TestCaseName

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
					out.println("\t// " + cov.getName() + ": " + cov.getQuality());
			}
			out.println("\tpublic void testFul" + testNumber + "() throws Exception {");
			out.println();

			{	// create variables

				// group references by type
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

			int tmpVarGenerator = 0;

			// generate code
			for(Operation op : test.getTest()) {
				OperationResult opResult = (OperationResult) op.getInfo(OperationResult.KEY);

				if(opResult == null) {
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
					switch(opResult.getStatus()) {
					case NOT_EXECUTED:
						out.println("\t\t//" + op + "; // Not Executed");
						break;

					case PRECONDITION_ERROR:
						out.println("\t\t//" + op + "; // Precondition Error");
						break;

					case POSTCONDITION_ERROR:
						out.println("\t\t" + op + "; //FIXME: this was a faulty invocation!");
						break;

					case SUCCESSFUL:
						if(op instanceof Invoke) {
							final Invoke invoke = (Invoke) op;

							final Clazz returnType = invoke.getMethod().getReturnType();
							if(returnType == null) {
								// the method does not return anything
								out.println("\t\t" + invoke + ";");

							} else {
								// create a temporary variable
								String tmpVar = "tmp" + (tmpVarGenerator++);

								// store the result in the tmp variable
								out.println("\t\t" + returnType.getClassName() + " " + tmpVar + " = " + new Invoke(null, invoke.getThis(), invoke.getMethod(), invoke.getParams()) + ";");

								// Put the value also in the original target
								final Reference target = invoke.getTarget();
								if(target != null) {
									final String cast;
									if(target.getClazz() instanceof PrimitiveClazz) {

										if(returnType instanceof PrimitiveClazz)
											cast = ((PrimitiveClazz) target.getClazz()).getCast();
										else
											cast = "(" + ((PrimitiveClazz) target.getClazz()).getReferenceClazz().getClassName() + ")";
									} else {
										cast = "(" + target.getClazz().getClassName() + ")";
									}

									out.println("\t\t" + target + " = " + cast + " " + tmpVar + ";");
								}

								generateAssertions("\t\t", out, opResult.getResult(), returnType.getClassName(), tmpVar);
							}

							if(invoke.getThis() != null)
								generateAssertions("\t\t", out, opResult.getObject(), null, invoke.getThis().toString());

							out.println();

						} else if(op instanceof CreateObject) {
							final CreateObject create = (CreateObject) op;

							if(create.getTarget() != null) {
								out.println("\t\t" + create + ";");
								generateAssertions("\t\t", out, opResult.getResult(), null, create.getTarget().toString());
								out.println();
							} else {

								// create a temporary variable and store there the created object
								String tmpVar = "tmp" + (tmpVarGenerator++);
								out.println("\t\t" + create.getConstructor().getClazz().getClassName() + " " + tmpVar + " = " + create + ";");
								generateAssertions("\t\t", out, opResult.getResult(), null, tmpVar);
								out.println();
							}


						} else

							out.println("\t\t" + op + ";");

						break;

					case EXCEPTIONAL:
						out.println("\t\ttry {");
						out.println("\t\t\t" + op + ";");

						// check if the exception thrown is public.
						String publicExcClass = getPublicException(opResult.getExcClassName());
						if(publicExcClass == null) {
							out.println("\t\t\tfail(\"Expecting a " + opResult.getExcClassName() + "\");");
							out.println("\t\t} catch(" + opResult.getExcClassName() + " e) {");
						} else {
							out.println("\t\t\tfail(\"Expecting a " + opResult.getExcClassName() + "\");");
							out.println("\t\t} catch(" + publicExcClass + " e) {");
							out.println("\t\t\tassertEquals(\"" + opResult.getExcClassName() + "\", e.getClass().getName());");
						}

						if(opResult.getExcMessage() != null)
							out.println("\t\t\tassertEquals(" + JavaUtils.escape(opResult.getExcMessage()) + ", e.getMessage());");

						if(op instanceof Invoke && !((Invoke)op).getMethod().isStatic())
							generateAssertions("\t\t\t", out, opResult.getObject(), null, ((Invoke)op).getThis().toString());

						out.println("\t\t}");
						out.println();

						break;
					}
				}
			}

			out.println("\t}");
		}

		/**
		 * check if the exception thrown is public.
		 * If it is the case, this method returns null;
		 * otherwise this method returns the name of the first public superclass
		 * @param excClassName
		 * @return
		 */
		private String getPublicException(String excClassName) {
			try {
				Class<?> excClass = classLoader.loadClass(excClassName);

				if(JavaUtils.isPublic(excClass)) return null;

				do {
					excClass = excClass.getSuperclass();
				} while(!JavaUtils.isPublic(excClass));

				return excClass.getName();

			} catch (ClassNotFoundException e) {
				logger.log(Level.WARNING, e.toString(), e);
				return "java.lang.Throwable";
			}
		}

		private void generateAssertions(String spaces, PrintWriter out, final Value result, final String varType, final String varName) {
			if(!createAssertions) return;
			if(result == null) return;

			if(result.isNull()) {
				out.println(spaces + "assertNull(" + varName + ");");

			} else {
				if(varType != null) {

					final Serializable object = result.getObject();
					if(object == null) {
						if(result.isNull()) out.println(spaces + "assertNull(" + varName + ");");
						else out.println(spaces + "assertNotNull(" + varName + ");");
					} else {
						generateSingleAssertion(spaces, out, object, varType, varName);
					}
				}

				for (String observer : result.getObservers())
					generateSingleAssertion(spaces, out, result.getObserver(observer), null, varName + "." + observer + "()");
			}
		}

		private void generateSingleAssertion(String spaces, PrintWriter out, final Serializable expected, final String varType, final String varName) {

			if(expected == null) {

				out.println(spaces + "assertNull(" + varName + ");");

			} else if(expected instanceof Boolean &&
					(varType == null || varType.equals("boolean") ||  varType.equals("java.lang.Boolean"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("boolean".equals(varType) ? "" : "(boolean)") + varName + ");");

			} else if(expected instanceof Byte &&
					(varType == null ||  varType.equals("byte") ||  varType.equals("java.lang.Byte"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("byte".equals(varType)  ? "" :  "(byte)") + varName + ");");

			} else if(expected instanceof Character &&
					(varType == null ||  varType.equals("char") ||  varType.equals("java.lang.Character"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("char".equals(varType)  ? "" :  "(char)") + varName + ");");

			} else if(expected instanceof String && (varType == null || varType.equals("java.lang.String"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " + varName + ");");

			} else if(expected instanceof Short &&
					(varType == null || varType.equals("short") || varType.equals("java.lang.Short"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("short".equals(varType)  ? "" :  "(short)") + varName + ");");

			} else if(expected instanceof Integer &&
					(varType == null || varType.equals("int") || varType.equals("java.lang.Integer"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("int".equals(varType)  ? "" :  "(int)") + varName + ");");

			} else if(expected instanceof Long &&
					( varType == null || varType.equals("long") || varType.equals("java.lang.Long"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("long".equals(varType)  ? "" :  "(long)") + varName + ");");

			} else if(expected instanceof Float &&
					(varType == null ||  varType.equals("float") ||  varType.equals("java.lang.Float"))) {

				// Assert.assertEquals(float, float, float) is buggy when NaN are used:
				// Assert.assertEquals(Float.NaN, Float.NaN, 0.001f) raises an assertionFaliedError! (discovered during the ICSE 2010 presentation! :( )
				// hence we use the Assert.assertEquals(double, double, double)
				// (note the epsilon parameter: it is a double!)
				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("float".equals(varType)  ? "" :  "(float)") + varName + ", 0.001);");

			} else if(expected instanceof Double &&
					(varType == null ||  varType.equals("double") ||  varType.equals("java.lang.Double"))) {

				out.println(spaces + "assertEquals(" + JavaUtils.escape(expected) + ", " +
						("double".equals(varType)  ? "" :  "(double)") + varName + ", 0.001);");

			}
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

		TestFul.printHeader("JUnit test generator");

		DataFinderCaching finder = null;
		RemoteClassLoader loader = null;
		try {
			finder = new DataFinderCaching(new DataFinderImpl(new ClassType(config)));
			loader = new RemoteClassLoader(finder);
		} catch (RemoteException e) {
			// never happens
			logger.log(Level.WARNING, "Remote exception (should never happen): " + e.toString(), e);
			System.exit(1);
		}

		JUnitTestGenerator gen = new JUnitTestGenerator(config.getDirGeneratedTests(), loader, false);

		if(config.noReduce)
			gen.read(config.tests);
		else
			gen.read(TestSuiteReducer.reduce(finder, config.reloadClasses, config.tests));

		gen.writeSuite();

		System.exit(0);
	}

	private static class Config extends ConfigProject implements IConfigProject.Args4j {

		@Option(required = false, name = "-reload", usage = "Reload classes before each run (reinitialize static fields)")
		private boolean reloadClasses;

		@Option(required = false, name = "-noReduce", usage = "Do not try to reduce test cases.")
		private  boolean noReduce;

		@Option(required = true, name = "-dirTests", usage = "Specify the directory in which generated tests will be put.")
		private File dirGeneratedTests;

		@Argument
		private List<String> tests = new ArrayList<String>();

		public File getDirGeneratedTests() {
			if(!dirGeneratedTests.isAbsolute()) dirGeneratedTests = new File(getDirBase(), dirGeneratedTests.getPath()).getAbsoluteFile();
			return dirGeneratedTests;
		}
	}
}
