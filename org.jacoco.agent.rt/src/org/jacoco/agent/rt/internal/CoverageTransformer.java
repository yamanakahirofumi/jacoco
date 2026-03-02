/*******************************************************************************
 * Copyright (c) 2009, 2026 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.agent.rt.internal;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.CodeSource;
import java.security.ProtectionDomain;

import org.jacoco.core.instr.Instrumenter;
import org.jacoco.core.runtime.AgentOptions;
import org.jacoco.core.runtime.IRuntime;
import org.jacoco.core.runtime.WildcardMatcher;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Class file transformer to instrument classes for code coverage analysis.
 */
public class CoverageTransformer implements ClassFileTransformer {

	private static final String AGENT_PREFIX;

	static {
		final String name = CoverageTransformer.class.getName();
		AGENT_PREFIX = toVMName(name.substring(0, name.lastIndexOf('.')));
	}

	private final Instrumenter instrumenter;

	private final IExceptionLogger logger;

	private final WildcardMatcher includes;

	private final WildcardMatcher excludes;

	private final WildcardMatcher exclClassloader;

	private final ClassFileDumper classFileDumper;

	private final boolean inclBootstrapClasses;

	private final boolean inclNoLocationClasses;

	private final boolean pertest;

	/**
	 * New transformer with the given delegates.
	 *
	 * @param runtime
	 *            coverage runtime
	 * @param options
	 *            configuration options for the generator
	 * @param logger
	 *            logger for exceptions during instrumentation
	 */
	public CoverageTransformer(final IRuntime runtime,
			final AgentOptions options, final IExceptionLogger logger) {
		this.instrumenter = new Instrumenter(runtime);
		this.logger = logger;
		// Class names will be reported in VM notation:
		includes = new WildcardMatcher(toVMName(options.getIncludes()));
		excludes = new WildcardMatcher(toVMName(options.getExcludes()));
		exclClassloader = new WildcardMatcher(options.getExclClassloader());
		classFileDumper = new ClassFileDumper(options.getClassDumpDir());
		inclBootstrapClasses = options.getInclBootstrapClasses();
		inclNoLocationClasses = options.getInclNoLocationClasses();
		pertest = options.getPertest();
	}

	public byte[] transform(final ClassLoader loader, final String classname,
			final Class<?> classBeingRedefined,
			final ProtectionDomain protectionDomain,
			final byte[] classfileBuffer) throws IllegalClassFormatException {

		// We do not support class retransformation:
		if (classBeingRedefined != null) {
			return null;
		}

		if (pertest) {
			if ("org/junit/runner/notification/RunNotifier".equals(classname)) {
				return instrumentJUnit4(classfileBuffer);
			}
			if ("org/junit/platform/launcher/core/TestExecutionListenerRegistry$CompositeTestExecutionListener"
					.equals(classname)) {
				return instrumentJUnit5(classfileBuffer);
			}
		}

		if (!filter(loader, classname, protectionDomain)) {
			return null;
		}

		try {
			classFileDumper.dump(classname, classfileBuffer);
			return instrumenter.instrument(classfileBuffer, classname);
		} catch (final Exception ex) {
			final IllegalClassFormatException wrapper = new IllegalClassFormatException(
					ex.getMessage());
			wrapper.initCause(ex);
			// Report this, as the exception is ignored by the JVM:
			logger.logException(wrapper);
			throw wrapper;
		}
	}

	/**
	 * Checks whether this class should be instrumented.
	 *
	 * @param loader
	 *            loader for the class
	 * @param classname
	 *            VM name of the class to check
	 * @param protectionDomain
	 *            protection domain for the class
	 * @return <code>true</code> if the class should be instrumented
	 */
	boolean filter(final ClassLoader loader, final String classname,
			final ProtectionDomain protectionDomain) {
		if (loader == null) {
			if (!inclBootstrapClasses) {
				return false;
			}
		} else {
			if (!inclNoLocationClasses
					&& !hasSourceLocation(protectionDomain)) {
				return false;
			}
			if (exclClassloader.matches(loader.getClass().getName())) {
				return false;
			}
		}

		return !classname.startsWith(AGENT_PREFIX) &&

				includes.matches(classname) &&

				!excludes.matches(classname);
	}

	/**
	 * Checks whether this protection domain is associated with a source
	 * location.
	 *
	 * @param protectionDomain
	 *            protection domain to check (or <code>null</code>)
	 * @return <code>true</code> if a source location is defined
	 */
	private boolean hasSourceLocation(final ProtectionDomain protectionDomain) {
		if (protectionDomain == null) {
			return false;
		}
		final CodeSource codeSource = protectionDomain.getCodeSource();
		if (codeSource == null) {
			return false;
		}
		return codeSource.getLocation() != null;
	}

	private byte[] instrumentJUnit4(final byte[] buffer) {
		final ClassReader reader = new ClassReader(buffer);
		final ClassWriter writer = new ClassWriter(reader,
				ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(final int access,
					final String name, final String descriptor,
					final String signature, final String[] exceptions) {
				final MethodVisitor mv = super.visitMethod(access, name,
						descriptor, signature, exceptions);
				if ("fireTestStarted".equals(name)
						&& "(Lorg/junit/runner/Description;)V"
								.equals(descriptor)) {
					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitCode() {
							super.visitCode();
							final Label start = new Label();
							final Label end = new Label();
							final Label handler = new Label();
							visitTryCatchBlock(start, end, handler,
									"java/lang/Throwable");
							visitLabel(start);
							visitMethodInsn(Opcodes.INVOKESTATIC,
									"org/jacoco/agent/rt/RT", "getAgent",
									"()Lorg/jacoco/agent/rt/IAgent;", false);
							visitVarInsn(Opcodes.ALOAD, 1);
							visitMethodInsn(Opcodes.INVOKEVIRTUAL,
									"org/junit/runner/Description",
									"getDisplayName", "()Ljava/lang/String;",
									false);
							visitMethodInsn(Opcodes.INVOKEINTERFACE,
									"org/jacoco/agent/rt/IAgent",
									"setSessionId", "(Ljava/lang/String;)V",
									true);
							visitLabel(end);
							final Label exit = new Label();
							visitJumpInsn(Opcodes.GOTO, exit);
							visitLabel(handler);
							visitInsn(Opcodes.POP);
							visitLabel(exit);
						}
					};
				}
				if ("fireTestFinished".equals(name)
						&& "(Lorg/junit/runner/Description;)V"
								.equals(descriptor)) {
					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitCode() {
							super.visitCode();
							final Label start = new Label();
							final Label end = new Label();
							final Label handler = new Label();
							visitTryCatchBlock(start, end, handler,
									"java/lang/Throwable");
							visitLabel(start);
							visitMethodInsn(Opcodes.INVOKESTATIC,
									"org/jacoco/agent/rt/RT", "getAgent",
									"()Lorg/jacoco/agent/rt/IAgent;", false);
							visitInsn(Opcodes.ICONST_1);
							visitMethodInsn(Opcodes.INVOKEINTERFACE,
									"org/jacoco/agent/rt/IAgent", "dump",
									"(Z)V", true);
							visitLabel(end);
							final Label exit = new Label();
							visitJumpInsn(Opcodes.GOTO, exit);
							visitLabel(handler);
							visitInsn(Opcodes.POP);
							visitLabel(exit);
						}
					};
				}
				return mv;
			}
		};
		reader.accept(visitor, 0);
		return writer.toByteArray();
	}

	private byte[] instrumentJUnit5(final byte[] buffer) {
		final ClassReader reader = new ClassReader(buffer);
		final ClassWriter writer = new ClassWriter(reader,
				ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		final ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(final int access,
					final String name, final String descriptor,
					final String signature, final String[] exceptions) {
				final MethodVisitor mv = super.visitMethod(access, name,
						descriptor, signature, exceptions);
				if ("executionStarted".equals(name)
						&& "(Lorg/junit/platform/launcher/TestIdentifier;)V"
								.equals(descriptor)) {
					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitCode() {
							super.visitCode();
							final Label start = new Label();
							final Label end = new Label();
							final Label handler = new Label();
							visitTryCatchBlock(start, end, handler,
									"java/lang/Throwable");
							visitLabel(start);
							visitVarInsn(Opcodes.ALOAD, 1);
							visitMethodInsn(Opcodes.INVOKEVIRTUAL,
									"org/junit/platform/launcher/TestIdentifier",
									"isTest", "()Z", false);
							final Label notTest = new Label();
							visitJumpInsn(Opcodes.IFEQ, notTest);
							visitMethodInsn(Opcodes.INVOKESTATIC,
									"org/jacoco/agent/rt/RT", "getAgent",
									"()Lorg/jacoco/agent/rt/IAgent;", false);
							visitVarInsn(Opcodes.ALOAD, 1);
							visitMethodInsn(Opcodes.INVOKEVIRTUAL,
									"org/junit/platform/launcher/TestIdentifier",
									"getDisplayName", "()Ljava/lang/String;",
									false);
							visitMethodInsn(Opcodes.INVOKEINTERFACE,
									"org/jacoco/agent/rt/IAgent",
									"setSessionId", "(Ljava/lang/String;)V",
									true);
							visitLabel(notTest);
							visitLabel(end);
							final Label exit = new Label();
							visitJumpInsn(Opcodes.GOTO, exit);
							visitLabel(handler);
							visitInsn(Opcodes.POP);
							visitLabel(exit);
						}
					};
				}
				if ("executionFinished".equals(name)
						&& "(Lorg/junit/platform/launcher/TestIdentifier;Lorg/junit/platform/engine/TestExecutionResult;)V"
								.equals(descriptor)) {
					return new MethodVisitor(Opcodes.ASM9, mv) {
						@Override
						public void visitCode() {
							super.visitCode();
							final Label start = new Label();
							final Label end = new Label();
							final Label handler = new Label();
							visitTryCatchBlock(start, end, handler,
									"java/lang/Throwable");
							visitLabel(start);
							visitVarInsn(Opcodes.ALOAD, 1);
							visitMethodInsn(Opcodes.INVOKEVIRTUAL,
									"org/junit/platform/launcher/TestIdentifier",
									"isTest", "()Z", false);
							final Label notTest = new Label();
							visitJumpInsn(Opcodes.IFEQ, notTest);
							visitMethodInsn(Opcodes.INVOKESTATIC,
									"org/jacoco/agent/rt/RT", "getAgent",
									"()Lorg/jacoco/agent/rt/IAgent;", false);
							visitInsn(Opcodes.ICONST_1);
							visitMethodInsn(Opcodes.INVOKEINTERFACE,
									"org/jacoco/agent/rt/IAgent", "dump",
									"(Z)V", true);
							visitLabel(notTest);
							visitLabel(end);
							final Label exit = new Label();
							visitJumpInsn(Opcodes.GOTO, exit);
							visitLabel(handler);
							visitInsn(Opcodes.POP);
							visitLabel(exit);
						}
					};
				}
				return mv;
			}
		};
		reader.accept(visitor, 0);
		return writer.toByteArray();
	}

	private static String toVMName(final String srcName) {
		return srcName.replace('.', '/');
	}

}
