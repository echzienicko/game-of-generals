package com.generals.api;

import com.generals.domain.ArmyFactory;
import com.generals.domain.Board;
import com.generals.domain.Rank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static reference data the client needs to build the placement screen and label pieces.
 * Served from the server so the rank names are defined in exactly one place.
 */
@RestController
@RequestMapping("/api/meta")
public class MetaController {

    public record RankDto(String name, String displayName, int power, boolean officer) {
    }

    public record MetaDto(int rows, int cols, int armySize,
                          List<RankDto> ranks, Map<String, Integer> roster) {
    }

    @GetMapping
    public MetaDto meta() {
        List<RankDto> ranks = List.of(Rank.values()).stream()
                .map(rank -> new RankDto(rank.name(), rank.displayName(), rank.power(), rank.isOfficer()))
                .toList();

        Map<String, Integer> roster = new LinkedHashMap<>();
        for (Rank rank : ArmyFactory.standardRoster()) {
            roster.merge(rank.name(), 1, Integer::sum);
        }
        return new MetaDto(Board.ROWS, Board.COLS, ArmyFactory.ARMY_SIZE, ranks, roster);
    }
}
