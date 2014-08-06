/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.tehuti.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.tehuti.Metric;
import org.tehuti.metrics.stats.*;
import org.tehuti.metrics.stats.Percentiles.BucketSizing;
import org.tehuti.utils.MockTime;
import org.junit.Test;

public class MetricsTest {

    private static double EPS = 0.000001;

    MockTime time = new MockTime();
    Metrics metrics = new Metrics(new MetricConfig(), Arrays.asList((MetricsReporter) new JmxReporter()), time);

    @Test
    public void testSimpleStats() throws Exception {
        ConstantMeasurable measurable = new ConstantMeasurable();
        metrics.addMetric("direct.measurable", measurable);
        Sensor s = metrics.sensor("test.sensor");
        s.add("test.avg", new Avg());
        s.add("test.max", new Max());
        s.add("test.min", new Min());
        s.add("test.rate", new Rate(TimeUnit.SECONDS));
        s.add("test.occurences", new Rate(TimeUnit.SECONDS, new SampledCount()));
        s.add("test.count", new SampledCount());
        s.add(new Percentiles(100, -100, 100, BucketSizing.CONSTANT, new Percentile("test.median", 50.0), new Percentile("test.perc99_9",
                                                                                                                         99.9)));

        Sensor s2 = metrics.sensor("test.sensor2");
        s2.add("s2.total", new Total());
        s2.record(5.0);

        for (int i = 0; i < 10; i++)
            s.record(i);

        // pretend 2 seconds passed...
        time.sleep(2000);

        assertEquals("s2 reflects the constant value", 5.0, metrics.getMetric("s2.total").value(), EPS);
        assertEquals("Avg(0...9) = 4.5", 4.5, metrics.getMetric("test.avg").value(), EPS);
        assertEquals("Max(0...9) = 9", 9.0, metrics.getMetric("test.max").value(), EPS);
        assertEquals("Min(0...9) = 0", 0.0, metrics.getMetric("test.min").value(), EPS);
        assertEquals("Rate(0...9) = 22.5", 22.5, metrics.getMetric("test.rate").value(), EPS);
        assertEquals("Occurences(0...9) = 5", 5.0, metrics.getMetric("test.occurences").value(), EPS);
        assertEquals("SampledCount(0...9) = 10", 10.0, metrics.getMetric("test.count").value(), EPS);
    }

    @Test
    public void testHierarchicalSensors() {
        Sensor parent1 = metrics.sensor("test.parent1");
        Metric parent1Count = parent1.add("test.parent1.count", new SampledCount());
        Sensor parent2 = metrics.sensor("test.parent2");
        Metric parent2Count = parent2.add("test.parent2.count", new SampledCount());
        Sensor child1 = metrics.sensor("test.child1", parent1, parent2);
        Metric child1Count = child1.add("test.child1.count", new SampledCount());
        Sensor child2 = metrics.sensor("test.child2", parent1);
        Metric child2Count = child2.add("test.child2.count", new SampledCount());
        Sensor grandchild = metrics.sensor("test.grandchild", child1);
        Metric grandchildCount = grandchild.add("test.grandchild.count", new SampledCount());

        /* increment each sensor one time */
        parent1.record();
        parent2.record();
        child1.record();
        child2.record();
        grandchild.record();

        double p1 = parent1Count.value();
        double p2 = parent2Count.value();
        double c1 = child1Count.value();
        double c2 = child2Count.value();
        double gc = grandchildCount.value();

        /* each metric should have a count equal to one + its children's count */
        assertEquals(1.0, gc, EPS);
        assertEquals(1.0 + gc, c1, EPS);
        assertEquals(1.0, c2, EPS);
        assertEquals(1.0 + c1, p2, EPS);
        assertEquals(1.0 + c1 + c2, p1, EPS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testBadSensorHiearchy() {
        Sensor p = metrics.sensor("parent");
        Sensor c1 = metrics.sensor("child1", p);
        Sensor c2 = metrics.sensor("child2", p);
        metrics.sensor("gc", c1, c2); // should fail
    }

    @Test
    public void testEventWindowing() {
        SampledCount sampledCount = new SampledCount();
        MetricConfig config = new MetricConfig().eventWindow(1).samples(2);
        sampledCount.record(config, 1.0, time.milliseconds());
        sampledCount.record(config, 1.0, time.milliseconds());
        assertEquals(2.0, sampledCount.measure(config, time.milliseconds()), EPS);
        sampledCount.record(config, 1.0, time.milliseconds()); // first event times out
        assertEquals(2.0, sampledCount.measure(config, time.milliseconds()), EPS);
    }

    @Test
    public void testTimeWindowing() {
        SampledCount sampledCount = new SampledCount();
        MetricConfig config = new MetricConfig().timeWindow(1, TimeUnit.MILLISECONDS).samples(2);
        sampledCount.record(config, 1.0, time.milliseconds());
        time.sleep(1);
        sampledCount.record(config, 1.0, time.milliseconds());
        assertEquals(2.0, sampledCount.measure(config, time.milliseconds()), EPS);
        time.sleep(1);
        sampledCount.record(config, 1.0, time.milliseconds()); // oldest event times out
        assertEquals(2.0, sampledCount.measure(config, time.milliseconds()), EPS);
    }

    @Test
    public void testOldDataHasNoEffect() {
        Max max = new Max();
        long windowMs = 100;
        int samples = 2;
        MetricConfig config = new MetricConfig().timeWindow(windowMs, TimeUnit.MILLISECONDS).samples(samples);
        max.record(config, 50, time.milliseconds());
        time.sleep(samples * windowMs);
        assertEquals(Double.NEGATIVE_INFINITY, max.measure(config, time.milliseconds()), EPS);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testDuplicateMetricName() {
        metrics.sensor("test").add("test", new Avg());
        metrics.sensor("test2").add("test", new Total());
    }

    @Test
    public void testQuotas() {
        Sensor sensor = metrics.sensor("test");
        sensor.add("test1.total", new Total(), new MetricConfig().quota(Quota.lessThan(5.0)));
        sensor.add("test2.total", new Total(), new MetricConfig().quota(Quota.moreThan(0.0)));
        sensor.record(5.0);
        try {
            sensor.record(1.0);
            fail("Should have gotten a quota violation.");
        } catch (QuotaViolationException e) {
            // this is good
        }
        assertEquals(6.0, metrics.metrics().get("test1.total").value(), EPS);
        sensor.record(-6.0);
        try {
            sensor.record(-1.0);
            fail("Should have gotten a quota violation.");
        } catch (QuotaViolationException e) {
            // this is good
        }
    }

    @Test
    public void testPercentiles() {
        int buckets = 100;
        Percentiles percs = new Percentiles(4 * buckets,
                                            0.0,
                                            100.0,
                                            BucketSizing.CONSTANT,
                                            new Percentile("test.p25", 25),
                                            new Percentile("test.p50", 50),
                                            new Percentile("test.p75", 75));
        MetricConfig config = new MetricConfig().eventWindow(50).samples(2);
        Sensor sensor = metrics.sensor("test", config);
        sensor.add(percs);
        Metric p25 = this.metrics.getMetric("test.p25");
        Metric p50 = this.metrics.getMetric("test.p50");
        Metric p75 = this.metrics.getMetric("test.p75");

        // record two windows worth of sequential values
        for (int i = 0; i < buckets; i++)
            sensor.record(i);

        assertEquals(25, p25.value(), 1.0);
        assertEquals(50, p50.value(), 1.0);
        assertEquals(75, p75.value(), 1.0);

        for (int i = 0; i < buckets; i++)
            sensor.record(0.0);

        assertEquals(0.0, p25.value(), 1.0);
        assertEquals(0.0, p50.value(), 1.0);
        assertEquals(0.0, p75.value(), 1.0);
    }

    public static class ConstantMeasurable implements Measurable {
        public double value = 0.0;

        @Override
        public double measure(MetricConfig config, long now) {
            return value;
        }

    }

    @Test
    public void testAllSamplesPurged() {
        long timeWindow = 10000;
        int samples = 2;
        MetricConfig config = new MetricConfig().timeWindow(timeWindow, TimeUnit.MILLISECONDS).samples(samples);
        Sensor sensor = metrics.sensor("test.testAllSamplesPurged", config);
        Metric rate = sensor.add("test.testAllSamplesPurged.qps", new OccurrenceRate());
        sensor.record(12345);
        time.sleep(1000);
        assertEquals(1.0, rate.value(), 0.0); // 1 QPS so far
        time.sleep(timeWindow * samples); // All samples should be purged on the next measurement
        assertEquals(0.0, rate.value(), 0.0); // We should get zero QPS, not NaN
    }

}
