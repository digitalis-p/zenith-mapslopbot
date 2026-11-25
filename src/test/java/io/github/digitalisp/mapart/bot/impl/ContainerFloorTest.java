package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code ContainerTask.floorY}: the stand row level of the walk close to a container ({@code build.containerColumnFloor}).
 * Made-up coordinates shaped like the soul sand sorter: front rows y74-75 at z -6..-5 opened from a walkway at z -4,
 * upper rows y77-79 at z -6 (y77 chests, y79 barrels) over the front rows' west part, far storage elsewhere.
 */
class ContainerFloorTest {
    private static RegionCfg region(Pos min, Pos max) {
        RegionCfg r = new RegionCfg();
        r.min = min;
        r.max = max;
        return r;
    }

    private static final RegionCfg FAR = region(new Pos(1256, 74, -9), new Pos(1317, 78, -7));
    private static final RegionCfg FRONT = region(new Pos(1000, 74, -6), new Pos(1125, 75, -5));
    private static final RegionCfg UPPER = region(new Pos(1000, 77, -6), new Pos(1053, 79, -6));
    private static final List<RegionCfg> SITE = List.of(FAR, FRONT, UPPER);

    @Test
    void anUpperBarrelIsWalkedToTheFrontRowsStandRow() {
        Pos barrel = new Pos(1020, 79, -6);
        assertEquals(74, ContainerTask.floorY(SITE, barrel, true));
        assertEquals(77, ContainerTask.floorY(SITE, barrel, false), "the kill switch: the holding region's lowest level");
        // the upper chests too: the same stand row as the walkway opens them from
        assertEquals(74, ContainerTask.floorY(SITE, new Pos(1020, 77, -6), true));
        assertEquals(77, ContainerTask.floorY(SITE, new Pos(1020, 77, -6), false));
    }

    @Test
    void singleRegionsKeepTheirOwnFloor() {
        assertEquals(74, ContainerTask.floorY(SITE, new Pos(1100, 75, -5), true), "front rows east of the upper rows");
        assertEquals(74, ContainerTask.floorY(SITE, new Pos(1300, 78, -8), true), "far storage");
        assertEquals(74, ContainerTask.floorY(SITE, new Pos(1300, 78, -8), false));
    }

    @Test
    void outsideEveryRegionTheContainersOwnLevel() {
        assertEquals(90, ContainerTask.floorY(SITE, new Pos(1500, 90, 40), true));
        assertEquals(90, ContainerTask.floorY(SITE, new Pos(1500, 90, 40), false));
        // above a region but outside all: the region below counts only within 5 levels
        assertEquals(78, ContainerTask.floorY(SITE, new Pos(1100, 78, -6), true), "front rows 4 below, but y78 lies in no region");
    }

    @Test
    void aDeepRegionBelowIsNotTheStandRow() {
        List<RegionCfg> regions = new ArrayList<>(SITE);
        regions.add(region(new Pos(1000, 60, -6), new Pos(1053, 66, -6))); // a basement 13 below the barrels
        assertEquals(74, ContainerTask.floorY(regions, new Pos(1020, 79, -6), true));
    }

    @Test
    void neverAboveTheHoldingRegionsFloor() {
        // a tall region holding the container from far below: its own floor stays
        RegionCfg tall = region(new Pos(2000, 60, 0), new Pos(2010, 80, 0));
        RegionCfg shelf = region(new Pos(2000, 76, 0), new Pos(2010, 77, 0));
        assertEquals(60, ContainerTask.floorY(List.of(tall, shelf), new Pos(2005, 79, 0), true));
    }

    @Test
    void incompleteRegionsAreIgnored() {
        List<RegionCfg> regions = new ArrayList<>(SITE);
        regions.add(0, region(null, new Pos(1053, 79, -6)));
        regions.add(0, null);
        assertEquals(74, ContainerTask.floorY(regions, new Pos(1020, 79, -6), true));
    }
}
