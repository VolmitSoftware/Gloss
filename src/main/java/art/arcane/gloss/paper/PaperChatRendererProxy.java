package art.arcane.gloss.paper;

import io.papermc.paper.event.player.AsyncChatEvent;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * A {@code ChatRenderer} built at runtime over the server's own interface. The interface is
 * resolved from {@code AsyncChatEvent#renderer()}, never named, because every one of its methods
 * carries Adventure types the shaded jar relocates; the proxy converts the per-viewer MiniMessage
 * string the chat engine produced into a native component through {@link ServerAdventure}.
 */
public final class PaperChatRendererProxy {
    private static final String RENDER = "render";
    private static final int VIEWER_ARGUMENT = 3;

    private PaperChatRendererProxy() {
    }

    /**
     * @param perViewer receives the {@code Audience} the server is rendering for and returns that
     *                  viewer's MiniMessage string
     */
    public static Object create(Function<Object, String> perViewer) throws ReflectiveOperationException {
        Class<?> rendererType = rendererType();
        return Proxy.newProxyInstance(rendererType.getClassLoader(), new Class<?>[]{rendererType},
            (proxy, method, arguments) -> invoke(perViewer, proxy, method, arguments));
    }

    public static Class<?> rendererType() throws ReflectiveOperationException {
        return AsyncChatEvent.class.getMethod("renderer").getReturnType();
    }

    private static Object invoke(Function<Object, String> perViewer, Object proxy, Method method,
                                 Object[] arguments) {
        if (method.getName().equals(RENDER) && arguments != null && arguments.length == 4) {
            return ServerAdventure.fromMiniMessage(perViewer.apply(arguments[VIEWER_ARGUMENT]));
        }
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "GlossChatRenderer";
            default -> throw new UnsupportedOperationException("chat renderer was asked for "
                + method.getName());
        };
    }
}
