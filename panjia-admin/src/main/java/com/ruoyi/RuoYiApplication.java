package com.ruoyi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;

/**
 * 启动程序
 * 
 * @author panjia
 */
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class RuoYiApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(RuoYiApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  盘家启动成功   ლ(´ڡ`ლ)ﾞ  \n" +
                "  ____    _    _   _ _____ ___    _    \n" +
                " |  _ \\  / \\  | \\ | |_   _|_ _|  / \\   \n" +
                " | |_) |/ _ \\ |  \\| | | |  | |  / _ \\  \n" +
                " |  __// ___ \\| |\\  | | |  | | / ___ \\ \n" +
                " |_|  /_/   \\_\\_| \\_| |_| |___/_/   \\_\\");
    }
}