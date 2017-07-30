

package tw.imonkey.serialpl2303;
public class Checksum {
    public static byte LRC(byte[] bytes) {
        byte iLRC = 0x00;
        for (byte aByte : bytes) {
            iLRC ^= aByte;
        }
        return iLRC;
    }

    public static byte SUM(byte[] bytes){
        byte iSUM  = 0x00;
        for (byte aByte : bytes) {
            iSUM += aByte;
        }
        return iSUM;
    }


    // Compute the MODBUS RTU CRC
    public static int CRC(byte[] bytes)
    {
        int icrc = 0xFFFF; //CRC16
        for (byte aByte : bytes) {
            icrc ^= aByte & 0xFF;   // XOR byte into least sig. byte of crc
            for (int i = 8; i != 0; i--) {    // Loop over each bit
                if ((icrc & 0x0001) != 0) {      // If the LSB is set
                    icrc >>= 1;                    // Shift right and XOR 0xA001
                    icrc ^= 0xA001;
                }
                else                            // Else LSB is not set
                    icrc >>= 1;                    // Just shift right
            }
        }
// Note, this number has low and high bytes swapped, so use it accordingly (or swap bytes)
        return icrc;
    }
}

