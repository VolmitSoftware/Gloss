package art.arcane.gloss.menu.action;

@FunctionalInterface
public interface MenuNavigator {
  NavigationResult navigate(NavigationRequest request);
}
