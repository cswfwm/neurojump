package com.neurojump.ai;

/** 离线检查：响应解析与字段提取对各类真实格式是否稳健。 */
public final class CoachCheck {
    public static void main(String[] a) {
        String resp = "{\"id\":\"x\",\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"{\\\"mutation\\\": 0.42, \\\"crossover\\\": 0.7, "
                + "\\\"eliteRatio\\\": 0.05, \\\"maxGenerations\\\": 500, "
                + "\\\"diversifyEvery\\\": 10}\"}}]}";
        String content = OpenAiClient.extractContent(resp);
        System.out.println("解析出的内容: " + content);
        System.out.println("包含 mutation: " + content.contains("mutation"));
        System.out.println("包含 diversifyEvery: " + content.contains("diversifyEvery"));

        // 字段提取：带空格、带引号、带顺序变化都要能读出来
        com.neurojump.train.Tuning t = new com.neurojump.train.Tuning();
        com.neurojump.train.Trainer trainer = new com.neurojump.train.Trainer(
                new java.util.ArrayList<>(), 8, 1);
        com.neurojump.ai.Coach coach = new com.neurojump.ai.Coach(trainer, null);
        boolean ok = coach.apply(content);
        System.out.println("应用成功: " + ok);
        System.out.println("mutation=" + trainer.tuning.mutation
                + " crossover=" + trainer.tuning.crossover
                + " elite=" + trainer.tuning.eliteRatio
                + " maxGen=" + trainer.tuning.maxGenerations
                + " diversify=" + trainer.tuning.diversifyEvery);
    }
}
