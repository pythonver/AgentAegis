package ascion.agent.aegis.spring.boot.starter.resolve;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * 降级方法搜索
 */
public class FallbackResolver {

    public static Method resolve(
            Class<?> targetClass,
            Method originalMethod,
            String fallbackName
    ) {
        Class<?>[] originalTypes = originalMethod.getParameterTypes();

        // 1. 优先尝试找：fallback(args..., Throwable)
        Class<?>[] typesWithEx = Arrays.copyOf(originalTypes, originalTypes.length + 1);
        typesWithEx[typesWithEx.length - 1] = Throwable.class;

        Method method = findMethodRecursive(targetClass, fallbackName, typesWithEx);

        // 2. 找不到带 Throwable 的，再尝试找：fallback(args...)
        if (method == null) {
            method = findMethodRecursive(targetClass, fallbackName, originalTypes);
        }

        // 3. 还是找不到，抛出异常
        if (method == null) {
            throw new IllegalStateException(
                    "找不到合法的 fallback 方法: " + fallbackName + " 在类 " + targetClass.getName() + "中"
            );
        }

        // 4. 校验返回值类型
        validateReturnType(originalMethod, method);

        // 5. 确保非 public 方法可以被调用
        method.setAccessible(true);

        return method;
    }

    /**
     * 递归向上查找方法（支持获取 private / protected 及其父类方法）
     */
    private static Method findMethodRecursive(
            Class<?> targetClass,
            String methodName,
            Class<?>[] parameterTypes
    ) {
        Class<?> current = targetClass;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass(); // 找不到就往父类找
            }
        }
        return null;
    }

    private static void validateReturnType(
            Method originalMethod,
            Method fallbackMethod
    ) {
        // isAssignableFrom 允许降级方法返回原方法返回值的子类，拓展性更好
        if (!originalMethod.getReturnType().isAssignableFrom(fallbackMethod.getReturnType())) {
            throw new IllegalStateException(
                    "Fallback 方法返回值类型不兼容: 原方法 ["
                            + originalMethod.getReturnType().getName()
                            + "] -> Fallback方法 ["
                            + fallbackMethod.getReturnType().getName()
                            + "]"
            );
        }
    }
}
