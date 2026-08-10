package com.tickpilot.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.tickpilot.budget.LoadLevel;

import org.junit.jupiter.api.Test;

/**
 * Enforces SPEC AC-14 mechanically rather than by review: <em>"the API does not require the
 * consumer to depend on internal classes"</em>.
 *
 * <p>Every public and protected member of the published package is walked, generic arguments
 * included, and anything from a {@code com.tickpilot} package other than {@code api} fails the
 * build. That is the whole of AC-14's first clause: a consumer can compile against this package
 * alone, and an internal type that leaks into a signature is caught here instead of in somebody
 * else's mod.
 *
 * <p>Also keeps {@link ServerLoad} in step with the internal load level it mirrors. The mapping
 * itself is an exhaustive switch and so is checked by the compiler; what the compiler cannot see
 * is a constant added on one side only, which is what this checks.
 */
class ApiSurfaceTest {
	/** Every type that makes up the published surface of SPEC FR-14. */
	private static final List<Class<?>> PUBLIC_API = List.of(
			TickPilotApi.class,
			TaskProfile.class,
			TaskProfile.Builder.class,
			TaskPriority.class,
			SubmitResult.class,
			ThrottlePolicy.class,
			ThrottleAdvice.class,
			ServerLoad.class,
			TickPilotMetrics.class);

	@Test
	void noPublicMemberExposesAnInternalClass() {
		List<String> leaks = new ArrayList<>();

		for (Class<?> type : PUBLIC_API) {
			check(type, type.getGenericSuperclass(), "superclass of " + type.getSimpleName(), leaks);

			for (Type implemented : type.getGenericInterfaces()) {
				check(type, implemented, "interface of " + type.getSimpleName(), leaks);
			}

			for (Method method : type.getDeclaredMethods()) {
				if (!isPublished(method.getModifiers())) {
					continue;
				}

				String where = type.getSimpleName() + "." + method.getName() + "()";
				check(type, method.getGenericReturnType(), "return type of " + where, leaks);

				for (Type parameter : method.getGenericParameterTypes()) {
					check(type, parameter, "parameter of " + where, leaks);
				}

				for (Type thrown : method.getGenericExceptionTypes()) {
					check(type, thrown, "exception of " + where, leaks);
				}
			}

			for (Constructor<?> constructor : type.getDeclaredConstructors()) {
				if (!isPublished(constructor.getModifiers())) {
					continue;
				}

				for (Type parameter : constructor.getGenericParameterTypes()) {
					check(type, parameter, "constructor parameter of " + type.getSimpleName(), leaks);
				}
			}

			for (Field field : type.getDeclaredFields()) {
				if (!isPublished(field.getModifiers())) {
					continue;
				}

				check(type, field.getGenericType(),
						"field " + type.getSimpleName() + "." + field.getName(), leaks);
			}
		}

		if (!leaks.isEmpty()) {
			fail("SPEC AC-14: the public API must not expose internal classes:\n  "
					+ String.join("\n  ", leaks));
		}
	}

	@Test
	void thePublishedSurfaceIsTheWholeApiPackage() {
		// A new class dropped into the package would otherwise escape the leak check above.
		// Nested types are covered through their enclosing class, so only top-level ones count.
		List<String> declared = PUBLIC_API.stream()
				.filter(type -> type.getEnclosingClass() == null)
				.map(Class::getSimpleName)
				.sorted()
				.toList();

		assertEquals(List.of("ServerLoad", "SubmitResult", "TaskPriority", "TaskProfile",
				"ThrottleAdvice", "ThrottlePolicy", "TickPilotApi", "TickPilotMetrics"), declared,
				"add the new API class to PUBLIC_API so it is checked too");
	}

	@Test
	void serverLoadMirrorsTheInternalLoadLevel() {
		assertEquals(Arrays.stream(LoadLevel.values()).map(Enum::name).toList(),
				Arrays.stream(ServerLoad.values()).map(Enum::name).toList(),
				"ServerLoad is the published mirror of LoadLevel; keep the constants and their "
						+ "order identical, or the mapping in TickPilotApi becomes a lie");
	}

	@Test
	void serverLoadIsOrderedFromCalmToWorst() {
		assertTrue(ServerLoad.CRITICAL.isAtLeast(ServerLoad.HIGH));
		assertTrue(ServerLoad.HIGH.isAtLeast(ServerLoad.HIGH));
		assertTrue(!ServerLoad.NORMAL.isAtLeast(ServerLoad.ELEVATED));
	}

	private static boolean isPublished(int modifiers) {
		return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
	}

	/** Walks a type and everything inside it: arguments, bounds, array components. */
	private static void check(Class<?> owner, Type type, String where, List<String> leaks) {
		if (type == null) {
			return;
		}

		if (type instanceof Class<?> raw) {
			checkClass(owner, raw, where, leaks);
		} else if (type instanceof ParameterizedType parameterized) {
			check(owner, parameterized.getRawType(), where, leaks);

			for (Type argument : parameterized.getActualTypeArguments()) {
				check(owner, argument, where, leaks);
			}
		} else if (type instanceof GenericArrayType array) {
			check(owner, array.getGenericComponentType(), where, leaks);
		} else if (type instanceof WildcardType wildcard) {
			for (Type bound : wildcard.getUpperBounds()) {
				check(owner, bound, where, leaks);
			}

			for (Type bound : wildcard.getLowerBounds()) {
				check(owner, bound, where, leaks);
			}
		} else if (type instanceof TypeVariable<?> variable) {
			for (Type bound : variable.getBounds()) {
				check(owner, bound, where, leaks);
			}
		}
	}

	private static void checkClass(Class<?> owner, Class<?> type, String where, List<String> leaks) {
		Class<?> component = type;

		while (component.isArray()) {
			component = component.getComponentType();
		}

		if (component.isPrimitive() || component.getPackage() == null) {
			return;
		}

		String packageName = component.getPackage().getName();

		if (packageName.startsWith("com.tickpilot") && !packageName.equals("com.tickpilot.api")) {
			leaks.add(component.getName() + " leaks through the " + where);
		}
	}
}
