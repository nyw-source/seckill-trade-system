import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTValidator;
import cn.hutool.jwt.signers.JWTSigner;
import cn.hutool.jwt.signers.JWTSignerUtil;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.time.Duration;
import java.util.Date;

/**
 * 压测用 JWT 批量签发器。
 *
 * <p>与网关 {@code AuthGlobalFilter.parseToken} / nyw-service {@code JwtTool.createToken} 使用
 * 完全相同的库（hutool-jwt）与密钥材料（nyw.jks），保证签出来的 token 能被网关逐字节校验通过。
 * 刻意不手写 JWS 拼接 —— hutool 的 header 字段顺序、base64url 去 padding 等细节一旦有偏差，
 * 签出来的 token 验签会静默失败，压测就变成全量 401。</p>
 *
 * <p>用法：TokenGen &lt;jks&gt; &lt;storePass&gt; &lt;alias&gt; &lt;起始userId&gt; &lt;数量&gt; &lt;输出csv&gt;</p>
 */
public class TokenGen {

    public static void main(String[] args) throws Exception {
        if (args.length < 6) {
            System.err.println("usage: TokenGen <jks> <pass> <alias> <startUserId> <count> <outCsv>");
            System.exit(2);
        }
        String jksPath = args[0];
        char[] pass = args[1].toCharArray();
        String alias = args[2];
        long startUser = Long.parseLong(args[3]);
        int count = Integer.parseInt(args[4]);
        String outCsv = args[5];

        // 1) 载入密钥库，取私钥 + 证书公钥（与 Spring 的 KeyStoreKeyFactory 取值方式一致）
        KeyStore ks = KeyStore.getInstance("JKS");
        try (InputStream in = new FileInputStream(jksPath)) {
            ks.load(in, pass);
        }
        Key key = ks.getKey(alias, pass);
        if (!(key instanceof PrivateKey)) {
            throw new IllegalStateException("别名 " + alias + " 下没有私钥");
        }
        Certificate cert = ks.getCertificate(alias);
        PublicKey publicKey = cert.getPublicKey();
        KeyPair keyPair = new KeyPair(publicKey, (PrivateKey) key);

        JWTSigner signer = JWTSignerUtil.createSigner("rs256", keyPair);
        System.out.println("keystore  : " + jksPath + "  alias=" + alias);
        System.out.println("算法      : " + publicKey.getAlgorithm() + " / " + publicKey.getFormat()
                + "  keySize=" + ((java.security.interfaces.RSAPublicKey) publicKey).getModulus().bitLength());

        long expMillis = System.currentTimeMillis() + Duration.ofHours(24).toMillis();

        try (PrintWriter pw = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(outCsv), StandardCharsets.UTF_8))) {
            pw.println("userId,token");
            for (int i = 0; i < count; i++) {
                long uid = startUser + i;
                String token = JWT.create()
                        .setPayload("user", uid)
                        .setExpiresAt(new Date(expMillis))
                        .setSigner(signer)
                        .sign();
                pw.println(uid + "," + token);
            }
        }

        // 2) 自检：随机抽 3 个 token，用与网关注册同一套逻辑验签 + 校验有效期 + 取 user
        String firstToken = JWT.create()
                .setPayload("user", startUser)
                .setExpiresAt(new Date(expMillis))
                .setSigner(signer).sign();
        JWT parsed = JWT.of(firstToken).setSigner(signer);
        boolean okVerify = parsed.verify();
        JWTValidator.of(parsed).validateDate();
        Object user = parsed.getPayload("user");
        System.out.println("自检      : verify=" + okVerify + "  user=" + user
                + "  payload=" + parsed.getPayloads());
        if (!okVerify || user == null) {
            throw new IllegalStateException("自检失败，token 无法通过 hutool 验签");
        }
        if (!String.valueOf(startUser).equals(String.valueOf(user))) {
            throw new IllegalStateException("payload user 不匹配: 期望 " + startUser + " 实际 " + user);
        }

        System.out.println("完成      : 已生成 " + count + " 个 token  ->  " + outCsv);
        System.out.println("有效期至  : " + new Date(expMillis));
    }
}
