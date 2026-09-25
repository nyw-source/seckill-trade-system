package e2e;

import com.nyw.seckill.domain.dto.SeckillOrderDTO;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Base64;

/**
 * 把 SeckillOrderDTO 序列化成 RabbitMQ 消息体（Base64），供验证脚本模拟「同一条消息重复投递」。
 *
 * <p>项目没有配置 Jackson 消息转换器，Spring AMQP 用的是默认 SimpleMessageConverter，
 * 即 Java 原生序列化 —— 管理界面手填 JSON 是投不进去的，只能按这个格式构造。</p>
 *
 * 用法：java -cp "test/e2e/bin;seckill-service/target/classes" e2e.DumpDto <orderId> <voucherId> <userId> <itemId>
 */
public class DumpDto {

    public static void main(String[] args) throws Exception {
        long orderId = Long.parseLong(args[0]);
        long voucherId = Long.parseLong(args[1]);
        long userId = Long.parseLong(args[2]);
        long itemId = Long.parseLong(args[3]);
        SeckillOrderDTO dto = new SeckillOrderDTO(orderId, voucherId, userId, itemId);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(dto);
        }
        System.out.print(Base64.getEncoder().encodeToString(bos.toByteArray()));
    }
}
