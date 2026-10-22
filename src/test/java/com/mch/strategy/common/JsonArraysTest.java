package com.mch.strategy.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class JsonArraysTest {

    @Test
    void writes_nan_as_null_and_reads_back() {
        double[] values = {142350000, 0.123456789012, Double.NaN, -0.000123456789, 3, Double.POSITIVE_INFINITY};
        String json = JsonArrays.write(values, 2);
        assertThat(json).isEqualTo("[142350000,0.123456789012,null,-1.2345679E-4,3,null]");

        double[] back = JsonArrays.read(json);
        assertThat(back[0]).isEqualTo(142350000);
        assertThat(back[1]).isEqualTo(0.123456789012);
        assertThat(back[2]).isNaN();
        assertThat(back[3]).isCloseTo(-0.000123456789, within(1e-10));
        assertThat(back[5]).isNaN();
    }

    @Test
    void reads_mysql_json_with_spaces() {
        assertThat(JsonArrays.read("[1, null, 2.5]")).containsExactly(1, Double.NaN, 2.5);
        assertThat(JsonArrays.read("[]")).isEmpty();
    }
}
