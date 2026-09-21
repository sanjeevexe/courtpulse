package com.courtpulse.api.http;

import com.courtpulse.query.CourtPulseQueryService;
import com.courtpulse.query.GameSnapshotReadModel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

@Validated
@RestController
@RequestMapping(path = "/api/v1/games", produces = MediaType.APPLICATION_JSON_VALUE)
public class GamesController {
    private final CourtPulseQueryService queries;

    public GamesController(CourtPulseQueryService queries) {
        this.queries = queries;
    }

    @Operation(summary = "List durable games")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "A stable keyset page of games"),
        @ApiResponse(responseCode = "400", description = "Invalid filter, limit, or cursor",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiDto.ProblemExample.class)))
    })
    @GetMapping
    public ApiDto.GamePage games(
            @Parameter(schema = @Schema(allowableValues = {"SCHEDULED", "LIVE", "FINAL"}))
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ApiDtoMapper.games(queries.games(status, limit, cursor));
    }

    @Operation(
            summary = "Read the current durable game snapshot",
            parameters = @Parameter(
                    name = "If-None-Match",
                    in = ParameterIn.HEADER,
                    description = "Previously returned snapshot validator",
                    schema = @Schema(type = "string")))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Current game snapshot",
                headers = @Header(name = "ETag", description = "Snapshot version validator",
                        schema = @Schema(type = "string"))),
        @ApiResponse(responseCode = "304", description = "Snapshot has not changed",
                headers = @Header(name = "ETag", description = "Snapshot version validator",
                        schema = @Schema(type = "string")),
                content = @Content),
        @ApiResponse(responseCode = "400", description = "Invalid game identifier",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiDto.ProblemExample.class))),
        @ApiResponse(responseCode = "404", description = "Unknown game",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiDto.ProblemExample.class)))
    })
    @GetMapping("/{gameId}")
    public ResponseEntity<ApiDto.GameSnapshot> game(
            @PathVariable(name = "gameId") @Size(max = 200) String gameId,
            WebRequest request) {
        GameSnapshotReadModel snapshot = queries.game(gameId);
        String etag = SnapshotEtag.of(snapshot);
        if (request.checkNotModified(etag)) {
            return null;
        }
        return ResponseEntity.ok().eTag(etag).body(ApiDtoMapper.snapshot(snapshot));
    }

    @Operation(summary = "Read canonical event history")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Events in sequence/revision/event order"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination input",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiDto.ProblemExample.class))),
        @ApiResponse(responseCode = "404", description = "Unknown game",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiDto.ProblemExample.class)))
    })
    @GetMapping("/{gameId}/events")
    public ApiDto.EventPage events(
            @PathVariable(name = "gameId") @Size(max = 200) String gameId,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "afterSequence", required = false) @Min(0) Long afterSequence) {
        return ApiDtoMapper.events(queries.events(gameId, limit, cursor, afterSequence));
    }

    @Operation(summary = "Read durable logical alerts for a game")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Alerts in stable reverse-chronological order"),
        @ApiResponse(responseCode = "400", description = "Invalid pagination input",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiDto.ProblemExample.class))),
        @ApiResponse(responseCode = "404", description = "Unknown game",
                content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        schema = @Schema(implementation = ApiDto.ProblemExample.class)))
    })
    @GetMapping("/{gameId}/alerts")
    public ApiDto.AlertPage alerts(
            @PathVariable(name = "gameId") @Size(max = 200) String gameId,
            @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(name = "cursor", required = false) String cursor) {
        return ApiDtoMapper.alerts(queries.alerts(gameId, limit, cursor));
    }
}
