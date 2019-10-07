# STCoproM4Example #

This application is a basic example of the proprietary CoproService usage on the STM32MPU reference devices.
The associated co-processor firmware is available by default in the associated [STM32MPU Embedded Software distribution for Android](https://wiki.st.com/stm32mpu/wiki/Category:STM32MPU_Embedded_Software_distribution_for_Android)

The principle is show the usage of several resources in the co-processor: TIM2, TIM7, DAC1, ADC1, DMA2, CRC2, HASH2 CRY2

The CRC32, SHA-256, AES-ECB results are getting back on the application and checked.
A sinusoid is generated on the co-processor DAC (which can be looped through a wire to the ADC input on the reference device).
The ADC results are getting back on the application and displayed.

## License ##

This module is distributed under the Apache License, Version 2.0 found in the [LICENSE](./LICENSE) file.
