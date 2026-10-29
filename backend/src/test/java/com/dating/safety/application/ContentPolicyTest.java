package com.dating.safety.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"иди нахуй", "Пиздец как скучно", "заебал этот город", "ну блядь", "бля",
            "х-у-й", "х у й", "xyй", "хуууй", "п1зд@", "ёбаный", "уебок", "мудак", "pizdec", "blyat", "гондон"})
    void obsceneWordsAreCaught(String text) {
        assertEquals(ContentPolicy.Category.OBSCENE, ContentPolicy.category(text).orElse(null), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Эскорт, индивидуалка", "секс за деньги", "интим за подарки", "минет", "ищу спонсора",
            "вирт по телефону"})
    void sexServicesAreCaught(String text) {
        assertEquals(ContentPolicy.Category.SEX_SERVICES, ContentPolicy.category(text).orElse(null), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"есть мефедрон", "кокаин", "Гашиш", "MDMA по выходным"})
    void drugsAreCaught(String text) {
        assertEquals(ContentPolicy.Category.DRUGS, ContentPolicy.category(text).orElse(null), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Страхую жизнь, работаю в страховой", "Хочу купить себе велосипед", "люблю небо",
            "команда мечты", "употреблять в пищу", "колеблюсь между двумя", "моральные принципы",
            "Интим не предлагать", "Похудела на 5 кг", "аналитик данных", "Хуан Карлос", "Хлебобулочные изделия",
            "Ребята, привет!", "Требую кофе", "оберег на удачу", "Мудрый совет", "спонсорство мероприятий",
            "застрахуемся", "Выхухоль — мой любимый зверь", "по-хорошему", "мы с ним", "Pink Floyd, Radiohead",
            "Hugo Boss", "Убежать от рутины", "Посуда", "в лес у реки"})
    void ordinaryTextPasses(String text) {
        assertTrue(ContentPolicy.category(text).isEmpty(), text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    void emptyTextPasses(String text) {
        assertTrue(ContentPolicy.violation(text).isEmpty());
    }
}
