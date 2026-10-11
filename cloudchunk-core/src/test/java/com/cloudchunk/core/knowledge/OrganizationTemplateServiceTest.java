package com.cloudchunk.core.knowledge;

import com.cloudchunk.common.exception.BizException;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class OrganizationTemplateServiceTest {
    @Test
    void boundedCanonicalFileSelectionAndExplicitMimeCategories() {
        assertThat(OrganizationTemplateService.parseSelection(List.of("101", "102")))
                .containsExactly(101L,102L);
        for (List<String> bad : List.of(
                List.<String>of(),List.of("10","10"),List.of("01"),
                List.of("-1"),List.of("1e3"),List.of("9223372036854775808"),
                java.util.Collections.nCopies(9,"101"))) {
            assertThatThrownBy(() -> OrganizationTemplateService.parseSelection(bad))
                    .isInstanceOf(BizException.class);
        }
        assertThat(OrganizationTemplateService.matches("TEXT","text/plain; charset=utf-8")).isTrue();
        assertThat(OrganizationTemplateService.matches("PDF","application/pdf")).isTrue();
        assertThat(OrganizationTemplateService.matches("IMAGE","image/png")).isTrue();
        assertThat(OrganizationTemplateService.matches("VIDEO","video/mp4")).isTrue();
        assertThat(OrganizationTemplateService.matches("PDF","text/plain")).isFalse();
        assertThat(OrganizationTemplateService.matches("ANY",null)).isFalse();
        assertThat(OrganizationTemplateService.matches("OTHER","image/png")).isFalse();
    }

    @Test
    void templateNameRejectsControlCharsAndUnboundedLabels() {
        assertThat(OrganizationTemplateService.checkedName("  Work documents  "))
                .isEqualTo("Work documents");
        for (String bad : List.of(" ", "bad\nname", "<script>", "a".repeat(81))) {
            assertThatThrownBy(() -> OrganizationTemplateService.checkedName(bad))
                    .isInstanceOf(BizException.class);
        }
    }
}
