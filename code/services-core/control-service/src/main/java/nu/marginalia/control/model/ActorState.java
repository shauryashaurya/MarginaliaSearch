package nu.marginalia.control.model;

import nu.marginalia.mqsm.graph.GraphState;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public record ActorState(String name,
                         boolean current,
                         List<String> transitions,
                         String description) {
    public ActorState(GraphState gs, boolean current) {
        this(gs.name(), current, toTransitions(gs.next(), gs.transitions()), gs.description());
    }
    private static List<String> toTransitions(String next, String[] transitions) {
        return Stream.concat(Stream.of(next), Arrays.stream(transitions)).distinct().toList();
    }
}
