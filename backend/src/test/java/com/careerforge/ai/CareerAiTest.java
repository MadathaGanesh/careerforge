package com.careerforge.ai;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class CareerAiTest {
  private final CareerAi ai=new CareerAi(null);
  @Test void matchesOnlySourceEvidence() {
    Map<String,Object> jd=Map.of("requirements",List.of(
      Map.of("id","r1","label","Spring Boot","priority","MANDATORY"),
      Map.of("id","r2","label","Kafka","priority","MANDATORY")));
    var result=ai.fit(jd,"Built secure REST APIs with Spring Boot and PostgreSQL.");
    assertEquals(50.0,(Double)result.get("alignmentScore"),.01);
    @SuppressWarnings("unchecked")var reqs=(List<FitRubric.Assessment>)result.get("requirements");
    assertEquals(FitRubric.Status.DEMONSTRATED, reqs.getFirst().status());
    assertEquals(FitRubric.Status.MISSING, reqs.get(1).status());
  }
  @Test void localModeNeverAddsUnsupportedAchievements(){
    String original="Worked as a Java Backend Developer using Spring Boot.";
    var proposals=ai.edits(original,"Java Backend role requiring Kafka");
    for(var p:proposals) {
      assertTrue(original.contains((String)p.get("originalText")));
      assertFalse(((String)p.get("proposedText")).toLowerCase().contains("kafka"));
    }
  }
  @Test void fallbackExtractionUsesTextOnly(){
    var extraction=ai.resume("Sample Candidate\nJava Backend Developer with PostgreSQL and Java.");
    assertTrue(((List<?>)extraction.get("skills")).contains("PostgreSQL"));
    assertEquals("LOCAL_HEURISTIC",extraction.get("mode"));
  }

  @Test void negatedSkillDoesNotCountAsDemonstratedEvidence() {
    var job = Map.<String, Object>of("requirements", List.of(Map.of("id", "java", "label", "Java", "priority", "MANDATORY")));
    assertEquals(0.0, ai.fit(job, "I have no Java experience.").get("alignmentScore"));
    assertEquals(100.0, ai.fit(job, "I had no Java experience then. I now build Java APIs.").get("alignmentScore"));
  }
  @Test void calibratedRubricUsesAliasesDurationAndOffsets() {
    var resume = "Worked with Postgres in production. 3 years of professional experience. Currently learning Java.";
    var job = Map.<String, Object>of("minExperienceYears", 5, "requirements", List.of(
            Map.of("id", "postgres", "label", "PostgreSQL", "priority", "MANDATORY"),
            Map.of("id", "java", "label", "Java", "priority", "PREFERRED")));
    var result = ai.fit(job, resume);
    assertEquals(67.86, result.get("alignmentScore"));
    @SuppressWarnings("unchecked")
    var rows = (List<FitRubric.Assessment>) result.get("requirements");
    assertEquals(FitRubric.Status.UNVERIFIED, rows.get(1).status());
    assertEquals(FitRubric.Status.PARTIAL, rows.get(2).status());
    var source = rows.getFirst().evidence().getFirst();
    assertEquals(resume.substring(source.start(), source.end()), source.excerpt());
  }

}

