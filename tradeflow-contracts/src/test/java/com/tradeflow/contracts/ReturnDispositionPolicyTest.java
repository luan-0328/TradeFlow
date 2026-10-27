package com.tradeflow.contracts;

import com.tradeflow.contracts.RefundContracts.Allocation;
import com.tradeflow.contracts.RefundContracts.ReturnDisposition;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验收认可与可售资格分离：不得缺省、漏件、换件或重复使用购买件。 */
class ReturnDispositionPolicyTest {
    private final List<Allocation> expected = List.of(new Allocation("10",1,8000),new Allocation("10",2,10000));
    @Test void acceptsExplicitMixedConditionsAndCanonicalizesOrder() {
        assertThat(ReturnDispositionPolicy.match(List.of(new ReturnDisposition("10",2,"DAMAGED"),new ReturnDisposition("10",1,"SALEABLE")),expected))
                .extracting(ReturnDisposition::disposition).containsExactly("SALEABLE","DAMAGED");
    }
    @Test void missingDispositionNeverDefaultsToSaleable() {
        assertThatThrownBy(() -> ReturnDispositionPolicy.match(List.of(),expected)).hasMessageContaining("逐件");
        assertThatThrownBy(() -> ReturnDispositionPolicy.canonical(List.of(new ReturnDisposition("10",1,null)))).hasMessageContaining("处置");
    }
    @Test void rejectsDuplicateUnitEvenWhenConditionDiffers() {
        assertThatThrownBy(() -> ReturnDispositionPolicy.match(List.of(new ReturnDisposition("10",1,"SALEABLE"),new ReturnDisposition("10",1,"SCRAPPED")),expected)).hasMessageContaining("重复");
    }
    @Test void rejectsMissingOrForeignUnit() {
        assertThatThrownBy(() -> ReturnDispositionPolicy.match(List.of(new ReturnDisposition("10",1,"QUARANTINE")),expected)).hasMessageContaining("购买件");
        assertThatThrownBy(() -> ReturnDispositionPolicy.match(List.of(new ReturnDisposition("10",1,"SALEABLE"),new ReturnDisposition("11",2,"SALEABLE")),expected)).hasMessageContaining("购买件");
    }
    @Test void rejectsUnknownDispositionAndInvalidIdentifier() {
        assertThatThrownBy(() -> ReturnDispositionPolicy.canonical(List.of(new ReturnDisposition("10",1,"UNKNOWN")))).hasMessageContaining("处置");
        assertThatThrownBy(() -> ReturnDispositionPolicy.canonical(List.of(new ReturnDisposition("01",1,"SALEABLE")))).hasMessageContaining("购买件");
    }
}
