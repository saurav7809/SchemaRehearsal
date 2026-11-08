package dev.rehearsal;

import java.util.List;

final class Risk {
    private Risk() {}

    record Finding(String source,String title,String severity,String table,String column,String statement,
                   String evidence,String recommendation) {
        int points() {
            return switch (severity) {
                case "CRITICAL" -> 45;
                case "HIGH" -> 26;
                case "MEDIUM" -> 12;
                default -> 5;
            };
        }
    }

    record Factor(String source,String title,int points) {}
    record Score(int score,String classification,int uncapped,List<Factor> factors) {}

    static Score score(List<Finding> findings) {
        var factors=findings.stream().distinct().map(f -> new Factor(f.source(),f.title(),f.points())).toList();
        int total=factors.stream().mapToInt(Factor::points).sum();
        int score=Math.min(100,total);
        String classification=score<=20 ? "LOW" : score<=40 ? "MEDIUM" : score<=70 ? "HIGH" : "CRITICAL";
        return new Score(score,classification,total,factors);
    }

    static Finding observed(String title,String severity,String evidence,String recommendation) {
        return new Finding("OBSERVED",title,severity,null,null,null,evidence,recommendation);
    }
}