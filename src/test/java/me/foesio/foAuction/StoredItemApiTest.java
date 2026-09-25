package me.foesio.foAuction;

import java.util.*;
import java.util.function.Consumer;
import me.foesio.foAuction.api.StoredItemApi.*;
import me.foesio.foAuction.model.*;
import me.foesio.foAuction.service.AuctionService;
import me.foesio.foAuction.storage.IUserDataRepository;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class StoredItemApiTest {
    Repo repo;
    AuctionService api;
    UUID owner;
    PlayerData data;
    AuctionListing listing;
    @BeforeEach void setup() {
        MockBukkit.mock(); repo = new Repo(); owner = UUID.randomUUID();
        data = new PlayerData(owner); repo.data.put(owner, data);
        listing = new AuctionListing(UUID.randomUUID(), owner, new ItemStack(Material.STICK, 2), 123.45, 100, Long.MAX_VALUE);
        data.getAuctions().add(listing);
        api = new AuctionService(null, repo, null, null, null, null, null, null);
        api.rebuildIndex();
    }
    @AfterEach void teardown() { MockBukkit.unmock(); }
    StoredItem snapshot() { return api.readItems(owner, Kind.LISTING, 0, 32).items().getFirst(); }
    Replacement replacement(StoredItem old) { return new Replacement(old, new ItemStack(Material.BLAZE_ROD, 2)); }

    @Test void listingUpdatePreservesAllTransactionFieldsAndBothCopies() {
        assertEquals(List.of(Result.UPDATED), api.replaceItems(owner, List.of(replacement(snapshot()))));
        var stored = data.getAuctions().getFirst(); var indexed = api.getListing(listing.getId());
        for (var next : List.of(stored, indexed)) {
            assertEquals(listing.getId(), next.getId()); assertEquals(owner, next.getSellerUuid());
            assertEquals(123.45, next.getPrice()); assertEquals(100, next.getCreatedAt());
            assertEquals(Long.MAX_VALUE, next.getExpiresAt());
            assertEquals(new ItemStack(Material.BLAZE_ROD, 2), next.getItem());
        }
        assertEquals(1, repo.saves); assertEquals(1, api.getItemRevision());
    }
    @Test void claimsPreserveIdentityAgeNoteAndMoneyClaims() {
        ClaimEntry money = ClaimEntry.money(37, "sale"); ClaimEntry item = ClaimEntry.item(new ItemStack(Material.STICK, 2), "expired");
        data.getClaims().addAll(List.of(money, item));
        var page = api.readItems(owner, Kind.CLAIM, 0, 32);
        assertEquals(1, page.items().size()); assertEquals(1, page.items().getFirst().index());
        api.replaceItems(owner, List.of(replacement(page.items().getFirst())));
        var next = data.getClaims().get(1);
        assertSame(money, data.getClaims().getFirst()); assertEquals(item.getId(), next.getId());
        assertEquals(item.getCreatedAt(), next.getCreatedAt()); assertEquals(item.getNote(), next.getNote());
        assertEquals(item.getMoney(), next.getMoney()); assertEquals(0, api.getItemRevision());
    }
    @Test void rejectsChangedRemovedAndMovedSnapshotsWithoutResurrectingItems() {
        var old = snapshot(); api.replaceItems(owner, List.of(replacement(old)));
        assertEquals(List.of(Result.STALE), api.replaceItems(owner, List.of(replacement(old))));
        data.getAuctions().clear(); api.rebuildIndex();
        assertEquals(List.of(Result.MISSING), api.replaceItems(owner, List.of(replacement(old))));
        assertEquals(1, repo.saves); assertNull(api.getListing(old.id()));
    }
    @Test void rejectsQuantityChangesAirAndForgedMoneyClaim() {
        var old = snapshot();
        for (var invalid : List.of(new ItemStack(Material.AIR), new ItemStack(Material.STICK, 3)))
            assertEquals(List.of(Result.INVALID), api.replaceItems(owner, List.of(new Replacement(old, invalid))));
        var money = ClaimEntry.money(37, "sale"); data.getClaims().add(money);
        var forged = new StoredItem(Kind.CLAIM, 0, money.getId(), old.item());
        assertEquals(List.of(Result.STALE), api.replaceItems(owner, List.of(replacement(forged))));
        assertEquals(0, repo.saves); assertSame(money, data.getClaims().getFirst());
    }
    @Test void pagesAreBoundedAndDoNotLoadUncachedOwners() {
        assertThrows(IllegalArgumentException.class, () -> api.readItems(owner, Kind.CLAIM, 0, 33));
        assertThrows(IllegalArgumentException.class, () -> api.readItems(owner, Kind.CLAIM, -1, 1));
        for (int i=0;i<40;i++) data.getClaims().add(ClaimEntry.money(1, ""));
        var page = api.readItems(owner, Kind.CLAIM, 0, 32);
        assertTrue(page.items().isEmpty()); assertTrue(page.hasMore()); assertEquals(32,page.nextOffset());
        assertFalse(api.readItems(UUID.randomUUID(),Kind.CLAIM,0,32).hasMore()); assertEquals(0,repo.loads);
    }
    @Test void defensiveCopiesAndNoOpDoNotPersist() {
        var old=snapshot(); var copy=old.item(); copy.setAmount(20);
        assertEquals(2,old.item().getAmount());
        assertEquals(List.of(Result.UNCHANGED), api.replaceItems(owner,List.of(new Replacement(old,old.item()))));
        assertEquals(0,repo.saves);
    }
    @Test void duplicateRequestsDoNotDuplicateRewardsOrSaves() {
        var request=replacement(snapshot());
        assertEquals(List.of(Result.UPDATED,Result.STALE),api.replaceItems(owner,List.of(request,request)));
        assertEquals(1,data.getAuctions().size()); assertEquals(1,repo.saves);
    }
    @Test void stalePurchaseScreenIsRejectedBeforeEconomyOrDelivery() {
        long rendered=api.getItemRevision(); api.replaceItems(owner,List.of(replacement(snapshot())));
        assertEquals(AuctionService.PurchaseResult.ITEM_CHANGED,api.buyListing(null,listing.getId(),rendered));
        assertNotNull(api.getListing(listing.getId())); assertEquals(1,data.getAuctions().size());
    }
    @Test void offThreadApiCallsAreRejected() throws Exception {
        var failure=new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread worker=new Thread(()->{try{api.readItems(owner,Kind.LISTING,0,1);}catch(Throwable e){failure.set(e);}});
        worker.start();worker.join();assertInstanceOf(IllegalStateException.class,failure.get());
    }
    @Test void aRemovedEarlierEntryCannotRedirectAnUpdateToAnotherListing() {
        var second = new AuctionListing(UUID.randomUUID(), owner, listing.getItem(), 555, 200, Long.MAX_VALUE);
        data.getAuctions().add(second); api.rebuildIndex();
        var stale = api.readItems(owner,Kind.LISTING,0,32).items().get(1);
        data.getAuctions().removeFirst(); api.rebuildIndex();
        assertEquals(List.of(Result.MISSING),api.replaceItems(owner,List.of(replacement(stale))));
        assertEquals(Material.STICK,api.getListing(second.getId()).getItemType()); assertEquals(0,repo.saves);
    }
    @Test void updatedRecordsSurviveStorageMapRoundTrips() {
        var originalClaim=ClaimEntry.item(new ItemStack(Material.STICK,2),"Expired auction");
        var updatedClaim=originalClaim.withItem(new ItemStack(Material.BLAZE_ROD,2));
        var roundClaim=ClaimEntry.fromMap(updatedClaim.toMap());
        assertEquals(updatedClaim.toMap(),roundClaim.toMap());
        var updatedListing=listing.withItem(new ItemStack(Material.BLAZE_ROD,2));
        var roundListing=AuctionListing.fromMap(updatedListing.toMap(),owner);
        assertEquals(updatedListing.toMap(),roundListing.toMap()); assertEquals(owner,roundListing.getSellerUuid());
    }
    @Test void reentrantMaintenanceCannotChangeAnOwnersClaimsDuringDelivery() {
        var player=MockBukkit.getMock().addPlayer();owner=player.getUniqueId();data=new PlayerData(owner);repo.data.put(owner,data);
        data.getClaims().add(ClaimEntry.item(new ItemStack(Material.STICK,2)));
        var request=replacement(api.readItems(owner,Kind.CLAIM,0,32).items().getFirst());
        repo.onLoad=()->assertEquals(List.of(Result.BUSY),api.replaceItems(owner,List.of(request)));
        assertEquals(AuctionService.ClaimResult.NOT_FOUND,api.claim(player,UUID.randomUUID()));
        repo.onLoad=null;
        assertEquals(List.of(Result.UPDATED),api.replaceItems(owner,List.of(request)));
    }
    static class Repo implements IUserDataRepository {
        Runnable onLoad;
        Map<UUID,PlayerData> data=new HashMap<>(); int saves,loads;
        public PlayerData getCached(UUID id){return data.get(id);}
        public Collection<PlayerData> getAllCached(){return data.values();}
        public void saveAsync(UUID id){saves++;}
        public PlayerData getOrCreate(UUID id){loads++;if(onLoad!=null)onLoad.run();return data.get(id);}
        public void loadAllFromDisk(){}
        public PlayerData loadAndCache(UUID id){throw new AssertionError("Unexpected I/O");}
        public void loadAndCacheAsync(UUID id,Consumer<PlayerData> callback){throw new AssertionError("Unexpected I/O");}
        public void saveNow(UUID id){throw new AssertionError("Unexpected blocking save");}
        public void saveAllAsync(){}
        public void shutdownAndFlush(){}
    }
}
