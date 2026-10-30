package me.foesio.foAuction.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import me.foesio.foAuction.storage.NpcSellerRepository;
import org.junit.jupiter.api.Test;

class AuctionPublicContractTest {
    @Test
    void npcIdentityIsStableAndNamesRejectFormatting() {
        assertEquals(NpcSellerRepository.idForName("Village Trader"),
                NpcSellerRepository.idForName(" village trader "));
        assertNotEquals(NpcSellerRepository.idForName("Village Trader"),
                NpcSellerRepository.idForName("Other Trader"));
        assertThrows(IllegalArgumentException.class,
                () -> NpcSellerRepository.idForName("&cInjected Color"));
    }
}
