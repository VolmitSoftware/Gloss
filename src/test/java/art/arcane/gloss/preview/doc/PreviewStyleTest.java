package art.arcane.gloss.preview.doc;

import art.arcane.gloss.api.IconBillboard;
import art.arcane.gloss.preview.PreviewElement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewStyleTest {
    @Test
    void labelsInheritRootStyleAndAllowIndependentBoxes() {
        CompiledPreviewDocument document = PreviewDocumentParser.parse("style.json", """
            {"textStyle":{"billboard":"vertical","scaleX":2,"textOpacity":170,"backgroundArgb":"#AA123456"},
             "elements":[{"type":"label","text":"'Inherited'","box":{"enabled":true,"padding":8,"borderWidth":3}},
                         {"type":"label","text":"'Override'","style":{"billboard":"center","scaleY":3}}]}
            """);
        List<PreviewElement> elements = document.build(PreviewStateContext.statics(Map.of()));
        PreviewElement.Label inherited = (PreviewElement.Label) elements.getFirst();
        PreviewElement.Label override = (PreviewElement.Label) elements.get(1);
        assertEquals(IconBillboard.VERTICAL, inherited.style().billboard());
        assertEquals(2F, inherited.style().scaleX());
        assertEquals(170, inherited.style().textOpacity());
        assertEquals(0xAA123456, inherited.backgroundColor());
        assertTrue(inherited.box().enabled());
        assertEquals(8, inherited.box().padding());
        assertEquals(IconBillboard.CENTER, override.style().billboard());
        assertEquals(1F, override.style().scaleX());
        assertEquals(3F, override.style().scaleY());
    }

    @Test
    void cardChromeUsesAuthoredGeometryAndIndependentColors() {
        CompiledPreviewDocument document = PreviewDocumentParser.parse("card.json", """
            {"card":{"title":"'Title'","minHalfWidth":20,"padding":10,"borderWidth":2,
              "trayPadding":6,"titleHeight":8,"titleGap":4,"backgroundArgb":"#40112233",
              "borderArgb":"#FF445566","titleArgb":"#60778899","trayArgb":"#FFABCDEF"},
             "elements":[{"type":"label","text":"'Body'"}]}
            """);
        List<PreviewElement> elements = document.build(PreviewStateContext.statics(Map.of()));
        PreviewElement.Panel border = (PreviewElement.Panel) elements.getFirst();
        PreviewElement.Panel panel = (PreviewElement.Panel) elements.get(1);
        PreviewElement.Panel title = (PreviewElement.Panel) elements.get(2);
        assertEquals(44, border.width());
        assertEquals(38, border.height());
        assertEquals(0xFF445566, border.color());
        assertEquals(40, panel.width());
        assertEquals(34, panel.height());
        assertEquals(0x40112233, panel.color());
        assertEquals(8, title.height());
        assertEquals(0x60778899, title.color());
    }

    @Test
    void cardPixelControlsRejectInvalidSizes() {
        assertThrows(IllegalArgumentException.class,
            () -> PreviewDocumentParser.parse("negative.json", "{\"card\":{\"padding\":-1}}"));
        assertThrows(IllegalArgumentException.class,
            () -> PreviewDocumentParser.parse("large.json", "{\"card\":{\"titleHeight\":257}}"));
    }
}
