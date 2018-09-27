package net.sf.jsslkeylog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Main transformer class that implements the required transformations to get
 * the SSL secrets.
 */
public class TransformerMain implements ClassFileTransformer {

	/**
	 * Classes to transform that contain RSA secrets (by Java version).
	 */
	private static String[] RSA_CLASSES = {
			"sun/security/ssl/RSAClientKeyExchange", // Java 7
			"sun/security/ssl/RSAClientKeyExchange$RSAClientKeyExchangeMessage", // Java 11
	};
	
	/**
	 * Class to transform that contains RSA secrets in server case.
	 */
	private static String RSA_PREMASTER_SECRET_CLASS= "sun/security/ssl/RSAKeyExchange$RSAPremasterSecret"; // Java 11

	
	/**
	 * Class to transform that contains CLIENT_RANDOM secrets.
	 */
	private static String HANDSHAKER_CLASS = "sun/security/ssl/Handshaker"; // Java 7
	
	/**
	 * Classes to transform to obtain CLIENT_RANDOM secrets.
	 */
	private static String[] CLIENT_KEY_EXCHANGE_CLASSES = { // Java 11
			  "sun/security/ssl/RSAClientKeyExchange$RSAClientKeyExchangeProducer",
			  "sun/security/ssl/RSAClientKeyExchange$RSAClientKeyExchangeConsumer",
			  "sun/security/ssl/ECDHClientKeyExchange$ECDHEClientKeyExchangeProducer",
			  "sun/security/ssl/ECDHClientKeyExchange$ECDHEClientKeyExchangeConsumer",
			  "sun/security/ssl/ECDHClientKeyExchange$ECDHClientKeyExchangeProducer",
			  "sun/security/ssl/ECDHClientKeyExchange$ECDHClientKeyExchangeConsumer",
			  "sun/security/ssl/DHClientKeyExchange$DHClientKeyExchangeProducer",
			  "sun/security/ssl/DHClientKeyExchange$DHClientKeyExchangeConsumer"
	};

	/**
	 * Agent entry point.
	 */
	public static void premain(String agentArgs, Instrumentation inst) throws IOException {
		List<String> rawClassNamesToInstrument = new ArrayList<String>();
		rawClassNamesToInstrument.addAll(Arrays.asList(RSA_CLASSES));
		rawClassNamesToInstrument.add(HANDSHAKER_CLASS);
		rawClassNamesToInstrument.add(RSA_PREMASTER_SECRET_CLASS);
		rawClassNamesToInstrument.addAll(Arrays.asList(CLIENT_KEY_EXCHANGE_CLASSES));
		Set<String> classNamesToInstrument = new HashSet<String>();
		for (String rawClassName : rawClassNamesToInstrument) {
			classNamesToInstrument.add(rawClassName.replace('/', '.'));
		}
		for (Class<?> c : inst.getAllLoadedClasses()) {
			if (classNamesToInstrument.contains(c.getName()))
				throw new IllegalStateException(c.getName() + " class already loaded");
		}
		inst.addTransformer(new TransformerMain());
		if (agentArgs.startsWith("=")) {
			agentArgs = agentArgs.substring(1);
			System.setProperty(LogWriter.VERBOSE_PROPERTY_NAME, "true");
			System.out.println("Verbose SSL logging activated");
		}
		System.setProperty(LogWriter.LOGFILE_PROPERTY_NAME, new File(agentArgs).getCanonicalPath());
		LogWriter.logLine("# SSL/TLS secrets log file, generated by jSSLKeyLog", null);
		System.out.println("Logging all SSL session keys to: " + agentArgs);
	}

	public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
		for (String rsaClass : RSA_CLASSES) {
			if (className.equals(rsaClass)) {
				return transform(classfileBuffer, new RSAClientKeyExchangeTransformer(rsaClass));
			}
		}
		if (className.equals(HANDSHAKER_CLASS)) {
			return transform(classfileBuffer, new HandshakerTransformer(HANDSHAKER_CLASS));
		}
		if (className.equals(RSA_PREMASTER_SECRET_CLASS)) {
			return transform(classfileBuffer, new RSAPremasterSecretTransformer(RSA_PREMASTER_SECRET_CLASS));
		}
		for (String clientKeyExchangeClass : CLIENT_KEY_EXCHANGE_CLASSES) {
			if (className.equals(clientKeyExchangeClass)) {
				return transform(classfileBuffer, new ClientKeyExchangeTransformer(clientKeyExchangeClass));
			}
		}
		return null;
	}

	private byte[] transform(byte[] classfileBuffer, AbstractTransformer transformer) {
		ClassReader cr = new ClassReader(classfileBuffer);
		ClassWriter cw = new ClassWriter(0);
		transformer.setNextVisitor(cw);
		cr.accept(transformer, 0);
		return cw.toByteArray();
	}
}
