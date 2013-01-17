package com.appnexus.java.tools;

import java.io.PrintStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.text.NumberFormat;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class SimpleProf {
	
	//TODO(SR): add option to inject an alternate shutdown hook; not sure if we can rely on jvm's shutdown hook
	//TODO(SR): catch VMDeath?
	//TODO(SR): skip native methods
	//TODO(SR): add thread id to method stats
	//TODO(SR): add frame info to method stats, i.e., list of callers
	
	// whether or not to log any auxiliary messages
	private static boolean silent; 
	private static PrintStream out = System.out;
	
	private final static Map<String, MethodStats> STACK_AWARE_METHOD_STATS;
	private final static int DEFAULT_MAP_SIZE = 10007;  // smallest prime > 10000
	
	private static final NumberFormat NUMBER_FORMAT = NumberFormat.getInstance();

	// black list of packages
	// i.e., if a class name starts with any of the below prefixes, it is excluded
	private static final String[] CLASS_BLACK_LIST = new String[] {
		"java.", "sun.", "com.apple.java", "scala.", "javax.",
		//N.B. do not instrument self!
		"com.appnexus.java.tools."
	};
	
	// white list of packages
	// if any of the below regular expressions matches a class, then it's potentially eligible for instrumentation
	private static String[] CLASS_WHITE_LIST = new String[] { ".*" };
	
	private static String[] METHOD_WHITE_LIST = new String[] { ".*" };
	
	static class MethodStats {
		long numInvocations;
		long elapsedTime;
		// used as a start time for the initial invocation 
		// avoids allocating the stack below if there are no recursive calls
		// (upon enterMethod aux is set to a non-negative value; upon exitMethod it's reset to -1 unless stack != null;
		// if aux is non-negative upon enterMethod, then we know it's a recursive call since previous enterMethod was not followed by exitMethod)
		long aux = -1; 
		LinkedList<Long> stack;
	}
	
	static {
		NUMBER_FORMAT.setMaximumFractionDigits(2);
		NUMBER_FORMAT.setGroupingUsed(true);
		// N.B. map acccess is always synchronized (on a method) except inside the shutdown hook; for that reason we need a thread-safe map
		STACK_AWARE_METHOD_STATS = new ConcurrentHashMap<String, MethodStats>(DEFAULT_MAP_SIZE);
	}
	
	protected static void logAux(String msg) {
		if (!silent) {
			out.println(SimpleProf.class.getName() + "::" + msg);
		}
	}
	
	protected static void log(String msg) {
		out.println(msg);
	}
	
	static String slashToDot(String s) {
		return s.replace('/', '.');
	}
	
	static String dotToSlash(String s) {
		return s.replace('.', '/');
	}
	
	private final static String fullyQualifiedName(String prefix, String suffix) {
		return prefix + "." + suffix;
	}
	
	public static void enterMethod(String className, String methodName) {
		String fqMethodName = fullyQualifiedName(className, methodName);

		synchronized (fqMethodName) {
			MethodStats stats = STACK_AWARE_METHOD_STATS.get(fqMethodName);
			
			if (stats == null) {
				// method being invoked for the first time
				stats = new MethodStats();
				STACK_AWARE_METHOD_STATS.put(fqMethodName, stats);
			}
			if (stats.aux == -1) {
				// non-recursive call
				stats.aux = System.nanoTime();
			} else {
				// recursive call, use the stack
				LinkedList<Long> stack = stats.stack;
				
				if (stack == null) {
					stack = new LinkedList<Long>();
					stats.stack = stack;
				}
				stack.push(System.nanoTime());
			}
		}
	}

	public static void exitMethod(String className, String methodName) {
		String fqMethodName = fullyQualifiedName(className, methodName);
		
		synchronized (fqMethodName) {
			MethodStats stats = STACK_AWARE_METHOD_STATS.get(fqMethodName);
			LinkedList<Long> stack = stats.stack;
			
			if (stack == null) {
				// exit from non-recursive call
				stats.elapsedTime += System.nanoTime() - stats.aux;
				stats.aux = -1;
			} else {
				// exit from recursive call
				stats.elapsedTime += System.nanoTime() - stack.pop();
				
				if (stack.isEmpty()) {
					stats.stack = null;
				}
			}
			stats.numInvocations++;
		}
	}
	
	private static void parseArguments(String args) {
		// args is comma-delimited list of key=value pairs
		String[] properties = args.split(",");
		
		if (properties != null) {
			// store properties in propMap
			for (int i = 0; i < properties.length; i++) {
				String[] property = properties[i].split("=");
				
				if (property.length != 2) {
					throw new RuntimeException("Arguments are not well-formed; expect comma-separated list of key=value pairs");
				}
				// remove leading/trailing whitespace from the key
				String key = property[0].trim();				
				String value = property[1];

				if (key.equalsIgnoreCase("silent")) {
					silent = Boolean.valueOf(value);
				} else if (key.equalsIgnoreCase("classes")) {
					CLASS_WHITE_LIST = value.split(";");
				} else if (key.equalsIgnoreCase("methods")) {
					METHOD_WHITE_LIST = value.split(";");
				}
			}
		}
	}

	public static void premain(String args, Instrumentation inst) {
		parseArguments(args);
		
		inst.addTransformer(new ASMTransformer());
		// add shutdown hook
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				log("Dumping stats: ");
				log("=======================================");

				for (Entry<String, MethodStats> e : STACK_AWARE_METHOD_STATS.entrySet()) {
					String fqMethodName = e.getKey();
					
					synchronized(fqMethodName) {
						MethodStats methodStats = e.getValue();
						long count = methodStats.numInvocations;
						long duration = methodStats.elapsedTime;
						//TODO: format
						log(fqMethodName + " : " + NUMBER_FORMAT.format(count) + " : " + 
							formatNanos(duration) + " : " + (count > 0 ? (formatNanos((double)duration/count)) : 0));
					}
				}
			}
		});
	}
	
	//TODO(SR): a more elegant implementation would count the zeroes
	private static String formatNanos(double nanos) {
		if (nanos > 1000) {
			double micros = nanos / 1000;
			
			if (micros > 1000) {
				double millis = micros / 1000;
				
				if (millis > 1000) {
					double seconds = millis / 1000;
					
					return NUMBER_FORMAT.format(seconds) + " sec";
				} else {
					return NUMBER_FORMAT.format(millis) + " ms";
				}
			} else {
				return NUMBER_FORMAT.format(micros) + " micros";
			}
		} else {
			return NUMBER_FORMAT.format(nanos) + " ns";
		}
	}
	
	private static class ASMTransformer implements ClassFileTransformer, Opcodes {

		public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
								byte[] classfileBuffer) throws IllegalClassFormatException {
			try {
				String cname = slashToDot(className);

				//TODO(SR): warn if a package matches boths lists?
				if (!SimpleProf.startsWith(cname, SimpleProf.CLASS_BLACK_LIST) && SimpleProf.matches(cname, SimpleProf.CLASS_WHITE_LIST)) {
					ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
					//TODO: do we really need to compute max stack/locals and frames?
					logAux("Begin visiting class: " + cname);
					logAux("Classloader: " + (loader == null ? "null" : loader.getClass()));
					
					ClassVisitor visitor = new MyClassVisitor(cw, cname);
					ClassReader reader = new ClassReader(classfileBuffer);
					reader.accept(visitor, 0);
					
					logAux("Done visiting class: " + cname);
					
					return cw.toByteArray();
				}
			} catch (Throwable ex) {
				ex.printStackTrace();
			}
			return classfileBuffer;
		}
	}
	
	// returns true iff some r in ss | s.matches(ss)
	static boolean matches(String s, String... ss) {
		assert s != null;
		
		if (ss == null) {
			return false;
		}
		for (String r : ss) {
			if (s.matches(r)) {
				return true;
			}
		}
		return false;
	}
	
	// returns true iff some r in ss | s.startsWith(r)
	static boolean startsWith(String s, String... ss) {
		assert s != null;
		
		if (ss == null) {
			return false; 
		}
		for (String r : ss) {
			if (s.startsWith(r)) {
				return true;
			}
		}
		return false;
	}

	private static class MyClassVisitor extends ClassAdapter implements Opcodes {
		private ClassVisitor cv;
		private String className;

		private MyClassVisitor(ClassVisitor cv, String className) {
			super(cv);
			this.cv = cv;
			this.className = className;
		}

		public MethodVisitor visitMethod(final int access, final String name, final String desc, final String signature,
				final String[] exceptions) {
			final MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
			//TODO(SR): format desc ala java
			String methodName = name + desc; 
			String fqMethodName = fullyQualifiedName(className, methodName);
			
			if (SimpleProf.matches(fqMethodName, SimpleProf.METHOD_WHITE_LIST)) {	
				return new SimpleMethodAdapter(mv, className, methodName);
			} else {
				return mv;
			}
		}
	}

	private static class SimpleMethodAdapter extends MethodAdapter implements Opcodes {
		private String className, methodName;

		private SimpleMethodAdapter(MethodVisitor visitor, String className, String methodName) {
			super(visitor);
			this.className = className;
			this.methodName = methodName;
		}

		public void visitCode() {
			// prepend: enterMethod(Thread.currentThread().getId(), className, methodName);
			this.visitLdcInsn(className);
			this.visitLdcInsn(methodName);
			this.visitMethodInsn(INVOKESTATIC, dotToSlash(SimpleProf.class.getName()), "enterMethod",
					"(Ljava/lang/String;Ljava/lang/String;)V");
			// original code
			super.visitCode();
		}

		public void visitInsn(int inst) {
			switch (inst) {
			case Opcodes.ARETURN:
			case Opcodes.DRETURN:
			case Opcodes.FRETURN:
			case Opcodes.IRETURN:
			case Opcodes.LRETURN:
			case Opcodes.RETURN:
			case Opcodes.ATHROW:
				// append: exitMethod(Thread.currentThread().getId(), className, methodName);
				this.visitLdcInsn(className);
				this.visitLdcInsn(methodName);
				this.visitMethodInsn(INVOKESTATIC, dotToSlash(SimpleProf.class.getName()), "exitMethod",
						"(Ljava/lang/String;Ljava/lang/String;)V");
				break;
			default:
				break;
			}
			super.visitInsn(inst);
		}
	}
}
