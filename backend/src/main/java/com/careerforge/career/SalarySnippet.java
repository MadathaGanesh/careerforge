package com.careerforge.career;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SalarySnippet {
    enum PayBasis { BASE_PAY, TOTAL_PAY, UNSPECIFIED }
    enum Experience { WITHIN_RANGE, BROADER, OUTSIDE_RANGE, UNSTATED }

    record Pay(BigDecimal low, BigDecimal high, PayBasis basis) {}
    private record Amounts(Pattern ranges, Pattern points) {}

    private static final String NUMBER = "(?:\\d{1,3}(?:,\\d{2,3})+|\\d{1,12})(?:\\.\\d{1,2})?";
    private static final String UNIT = "(?:lakhs?|lacs?|lpa|crores?|cr|[lkm])\\b";
    private static final String BOUNDARY = "(?<![\\p{L}\\d.,-])";
    private static final String YEAR_RANGE = "(\\d{1,2})(?:\\s*(?:[-–—]|\\bto\\b)\\s*(\\d{1,2}))?\\s*(?:years?|yrs?)\\b";
    private static final List<Pattern> EXPERIENCE = List.of(
            Pattern.compile("(?iu)(?<![.\\d-])" + YEAR_RANGE + "[’']?\\s*(?:of\\s+)?(?:work\\s+)?(?:experience|exp\\b)"),
            Pattern.compile("(?iu)\\bexperience\\s*:\\s*" + YEAR_RANGE),
            Pattern.compile("(?iu)\\b(?:salary|pay|compensation)\\s+for\\s+" + YEAR_RANGE));
    private static final Pattern ANNUAL = Pattern.compile(
            "(?iu)\\b(?:lpa|annual(?:ly)?|yearly|per\\s+(?:year|annum))\\b|/\\s*(?:year|yr)\\b");
    private static final Pattern NON_ANNUAL = Pattern.compile(
            "(?iu)\\b(?:monthly|hourly|weekly|per\\s+(?:month|hour|week))\\b|/\\s*(?:mo|month|hr|hour|wk|week)\\b");
    private static final Pattern BASE = Pattern.compile("(?iu)\\b(?:base|fixed)\\s+(?:salary|pay|compensation|ctc)\\b");
    private static final Pattern TOTAL = Pattern.compile("(?iu)\\b(?:(?<!fixed\\s)ctc(?!\\s+exclud)|cost to company|total\\s+(?:pay|salary|compensation))\\b");
    private static final Pattern PAY = Pattern.compile("(?iu)\\b(?:salar(?:y|ies)|pay|compensation|ctc|lpa|earnings)\\b");
    private static final Pattern CURRENCY_MARKER = Pattern.compile("(?iu)(?:\\b(?:US|CA|AU|A|C)\\$|[₹£€$]|\\b(?:INR|USD|GBP|EUR|CAD|AUD)\\b|\\bRs\\.?\\s*(?=\\d))");
    private static final Map<SalaryMarketResearch.Currency, Amounts> MONEY = new EnumMap<>(SalaryMarketResearch.Currency.class);

    static {
        for (var currency : SalaryMarketResearch.Currency.values()) {
            String prefix = switch (currency) {
                case INR -> "₹|\\bINR\\b|\\bRs\\.?\\s*(?=\\d)";
                case USD -> "US\\$|\\bUSD\\b|(?<![A-Za-z])\\$";
                case GBP -> "£|\\bGBP\\b";
                case EUR -> "€|\\bEUR\\b";
                case CAD -> "(?:CA|C)\\$|\\bCAD\\b";
                case AUD -> "(?:AU|A)\\$|\\bAUD\\b";
            };
            String first = amount(prefix, "A", BOUNDARY);
            String second = amount(prefix, "B", "");
            MONEY.put(currency, new Amounts(Pattern.compile("(?iu)" + first + "\\s*(?:[-–—]|\\bto\\b)\\s*" + second),
                    Pattern.compile("(?iu)" + first)));
        }
    }

    private static String amount(String prefix, String suffix, String boundary) {
        return "(?:(?<currency" + suffix + ">" + prefix + ")\\s*|" + boundary + ")(?<number" + suffix + ">" + NUMBER
                + ")(?!\\d|[.,]\\d)(?:\\s*(?<unit" + suffix + ">" + UNIT + "))?";
    }

    static boolean salaryPage(String titleAndExcerpt) {
        return PAY.matcher(titleAndExcerpt).find();
    }

    static Pay read(String excerpt, SalaryMarketResearch.Currency currency) {
        Amounts patterns = MONEY.get(currency);
        Matcher ranges = patterns.ranges().matcher(excerpt);
        Pay match = null;
        int rangesFound = 0;
        while (ranges.find()) {
            String firstUnit = ranges.group("unitA");
            String secondUnit = ranges.group("unitB");
            if (!hasMoney(ranges, firstUnit, secondUnit, currency)) continue;
            rangesFound++;
            String context = payContext(excerpt, ranges);
            if (!annual(context) || rangesFound > 1) return null;
            if (firstUnit == null && number(ranges, "A").compareTo(BigDecimal.valueOf(1000)) < 0) firstUnit = secondUnit;
            if (secondUnit == null && number(ranges, "B").compareTo(BigDecimal.valueOf(1000)) < 0) secondUnit = firstUnit;
            match = pay(number(ranges, "A"), firstUnit, number(ranges, "B"), secondUnit, currency, context);
        }
        if (rangesFound > 0) return match;

        Matcher points = patterns.points().matcher(excerpt);
        while (points.find()) {
            String unit = points.group("unitA");
            boolean monetary = points.group("currencyA") != null || indiaUnit(unit, currency);
            if (!monetary || !annual(payContext(excerpt, points))) continue;
            if (match != null) return null;
            BigDecimal amount = number(points, "A");
            match = pay(amount, unit, amount, unit, currency, payContext(excerpt, points));
        }
        return match;
    }

    private static boolean hasMoney(Matcher match, String a, String b, SalaryMarketResearch.Currency currency) {
        return match.group("currencyA") != null || match.group("currencyB") != null
                || indiaUnit(a, currency) || indiaUnit(b, currency);
    }

    private static boolean indiaUnit(String unit, SalaryMarketResearch.Currency currency) {
        if (currency != SalaryMarketResearch.Currency.INR || unit == null) return false;
        String normalized = unit.toLowerCase(Locale.ROOT);
        return normalized.startsWith("l") || normalized.startsWith("cr");
    }

    static Experience experience(String text, SalaryMarketResearch.Criteria criteria) {
        boolean any = false, within = false, outside = false;
        for (Pattern pattern : EXPERIENCE) {
            Matcher experience = pattern.matcher(text);
            while (experience.find()) {
                int from = Integer.parseInt(experience.group(1));
                int to = experience.group(2) == null ? from : Integer.parseInt(experience.group(2));
                if (from > to) continue;
                any = true;
                if (criteria.experienceFrom() <= from && to <= criteria.experienceTo()) {
                    within = true;
                } else {
                    if (from <= criteria.experienceTo() && to >= criteria.experienceFrom()) return Experience.BROADER;
                    outside = true;
                }
            }
        }
        if (!any) return Experience.UNSTATED;
        if (within && outside) return Experience.BROADER;
        return within ? Experience.WITHIN_RANGE : Experience.OUTSIDE_RANGE;
    }

    private static boolean annual(String context) {
        return ANNUAL.matcher(context).find() && !NON_ANNUAL.matcher(context).find();
    }

    private static String payContext(String text, Matcher amount) {
        return text.substring(Math.max(0, amount.start() - 40), Math.min(text.length(), amount.end() + 39));
    }

    private static BigDecimal number(Matcher match, String suffix) {
        return new BigDecimal(match.group("number" + suffix).replace(",", ""));
    }

    private static Pay pay(BigDecimal first, String firstUnit, BigDecimal second, String secondUnit,
                           SalaryMarketResearch.Currency currency, String context) {
        BigDecimal low = scaled(first, firstUnit, currency);
        BigDecimal high = scaled(second, secondUnit, currency);
        boolean base = BASE.matcher(context).find();
        boolean total = TOTAL.matcher(context).find();
        if (low == null || high == null || low.signum() <= 0 || high.compareTo(low) < 0
                || high.compareTo(BigDecimal.valueOf(1_000_000_000)) > 0
                || base && total || !currencyMatches(context, currency)) return null;
        return new Pay(low.stripTrailingZeros(), high.stripTrailingZeros(), base ? PayBasis.BASE_PAY
                : total ? PayBasis.TOTAL_PAY : PayBasis.UNSPECIFIED);
    }

    private static BigDecimal scaled(BigDecimal value, String unit, SalaryMarketResearch.Currency currency) {
        if (unit == null) return value;
        if (indiaUnit(unit, currency)) return value.movePointRight(unit.toLowerCase(Locale.ROOT).startsWith("cr") ? 7 : 5);
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "k" -> value.movePointRight(3);
            case "m" -> value.movePointRight(6);
            default -> null;
        };
    }

    private static boolean currencyMatches(String context, SalaryMarketResearch.Currency currency) {
        Matcher marker = CURRENCY_MARKER.matcher(context);
        while (marker.find()) {
            String code = switch (marker.group().strip().toUpperCase(Locale.ROOT)) {
                case "₹", "RS", "RS." -> "INR";
                case "US$", "$" -> "USD";
                case "£" -> "GBP";
                case "€" -> "EUR";
                case "CA$", "C$" -> "CAD";
                case "AU$", "A$" -> "AUD";
                default -> marker.group().toUpperCase(Locale.ROOT);
            };
            if (!currency.name().equals(code)) return false;
        }
        return true;
    }
}
