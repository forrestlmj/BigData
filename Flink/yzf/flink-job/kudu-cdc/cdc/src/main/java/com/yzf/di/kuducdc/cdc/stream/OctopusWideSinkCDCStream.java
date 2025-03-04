package com.yzf.di.kuducdc.cdc.stream;


import com.yzf.di.kuducdc.cdc.sink.OctopusKuduSink;
import com.yzf.di.kuducdc.cdc.source.OctopusKafkaSource;
import com.yzf.di.kuducdc.cdc.util.OctopusDataValidator;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.types.Row;
import org.apache.flink.util.Collector;

/**
 *
 * 1、从kafka消费数据。
 * 2、在map算子中，sleep一段时间，强制静态控流，避免占用下一步sink算子的kudu资源;同时将算子并行度设置为1，让算子之间并行度不一致便于产生背压。
 * 3.1、在flatmap中将数据拉平，准备写入窄表json。经2021年7月线上观测评价一条json会被拆分100份，因此上一步算子的静态控流很重要。
 * 3.2、在map中解析宽json，准备写入宽表。
 * 4.1、窄表json通过sink算子写入到kudu表。
 * 4.2、宽表json通过sink算子吸入kudu表。
 */
public class OctopusWideSinkCDCStream {
    /** 校验JSON中的格式，如果没有key则补充Key
     * https://blog.csdn.net/liuxiao723846/article/details/108578544
     * @param
     * @return
     */
//    private static final Logger logger = LoggerFactory.getLogger(OctopusWideSinkCDCStream.class);
    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        env.setParallelism(1);
        // 3分钟一次ck
        env.enableCheckpointing(1000*60*3);
        ParameterTool tool = ParameterTool.fromArgs(args);
        //线下 ：--kudu_master 172.24.10.2:7051 --bootstrap 172.24.215.5:9093
        //线上： --kudu_master 172.16.1.249:7051,172.16.3.19:7051,172.16.5.185:7051,172.16.6.192:7051,172.16.1.5:7051 --bootstrap 172.16.190.35:9092,172.16.190.36:9092,172.16.190.37:9092
        //线上+章鱼报表： --kudu_master 172.16.1.249:7051,172.16.3.19:7051,172.16.5.185:7051,172.16.6.192:7051,172.16.1.5:7051 --bootstrap 172.20.150.1:2181
        //线上测试： --kudu_master 172.16.1.249:7051,172.16.3.19:7051,172.16.5.185:7051,172.16.6.192:7051,172.16.1.5:7051 --bootstrap 172.24.215.5:9093

        String KUDU_MASTER = tool.get("kudu_master");
        String BOOTSTRAP = tool.get("bootstrap");
        // --max_process_amount 500
        Integer MAX_PROCESS_AMOUNT = Integer.valueOf(tool.get("max_process_amount", "500"));
        // 0、接受数据
//        DataStream<ObjectNode> source = new OctopusAutoGenerateSource().randomPressureSource(env);
        DataStream<ObjectNode> source = new OctopusKafkaSource().source(env, BOOTSTRAP);
        // 1、控流
        SingleOutputStreamOperator<ObjectNode> map = source
                .map(new MapFunction<ObjectNode, ObjectNode>() {
                    // 强制sleep做限流
                    @Override
                    public ObjectNode map(ObjectNode jsonNodes) throws Exception {
                        Thread.sleep(1000/MAX_PROCESS_AMOUNT);
                        return jsonNodes;
                    }
                }).setParallelism(1);

            String[] columns_qushu_table = new String[]{"id","kjnd_kjqj","cell_location","cell_is_change","cell_value","area_id","area_name","box_id","create_time","dz_qy_id","dz_qy_name","fetch_data_time","kjnd","kjqj","nsqxdm","parent_box_id","qy_id","qy_name","sbsz_id","sheet_name","system_id"};
        String octopus_qushu_table = "impala::yzf_report.octopus_qushu_table";


        SingleOutputStreamOperator<Row> narrow_column = map.flatMap(new FlatMapFunction<ObjectNode, Row>() {
            @Override
            public void flatMap(ObjectNode value, Collector<Row> out) throws Exception {
                ObjectNode data = (ObjectNode) value.get("value");
                data = OctopusDataValidator.JsonSchemaValidator(data);
                // 主键设置默认值，防止kudusink出现错误。id、kjnd、kjqj、cell_location
                String kjnd_kjqj = OctopusDataValidator.DateValidator(data.get("kjnd").asText("2021") , data.get("kjqj").asText("1"));
//                logger.info(data.get("id").asText()+":"+data.get("createTime").asText());

                for (JsonNode cell : data.findValue("cells")) {
                    Row row = new Row(columns_qushu_table.length);

                    row.setField(0, data.get("id").asText("NULL"));

                    row.setField(1,Integer.valueOf(kjnd_kjqj));

                    if (cell.get("location") == null) {
                        row.setField(2, "");
                    } else {
                        row.setField(2, cell.get("location").asText("NULL"));
                    }

                    if (cell.get("isChange") == null) {
                        row.setField(3, "");
                    } else {
                        row.setField(3, cell.get("isChange").asText());
                    }
                    if (cell.get("value") == null) {
                        row.setField(4, "");
                    } else {
                        row.setField(4, cell.get("value").asText());
                    }

                    row.setField(5, data.get("areaId").asText());
                    row.setField(6, data.get("areaName").asText());
                    row.setField(7, data.get("boxId").asText());
                    row.setField(8, data.get("createTime").asText());
                    row.setField(9, data.get("dzQyId").asText());
                    row.setField(10, data.get("dzQyName").asText());
                    row.setField(11, data.get("fetchDataTime").asLong());
                    row.setField(12, data.get("kjnd").asText());
                    row.setField(13, data.get("kjqj").asText());
                    row.setField(14, data.get("nsqxdm").asText());
                    row.setField(15, data.get("parentBoxId").asText());
                    row.setField(16, data.get("qyId").asText());
                    row.setField(17, data.get("qyName").asText());
                    row.setField(18, data.get("sbszId").asText());
                    row.setField(19, data.get("sheetName").asText());
                    row.setField(20, data.get("systemId").asText());

                    out.collect(row);
                }
            }
        }).setParallelism(2);
        // 3、写入
        // 分别写入到kudu窄表与宽表中
        narrow_column.addSink(new OctopusKuduSink().sink(env,KUDU_MASTER,octopus_qushu_table,columns_qushu_table))
                .setParallelism(1);

//        narrow_column.print();
        env.execute("octopus cdc from kafka to kudu");
    }
}
