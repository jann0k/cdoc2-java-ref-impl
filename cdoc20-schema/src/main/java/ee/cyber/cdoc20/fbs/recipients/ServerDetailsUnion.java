// automatically generated by the FlatBuffers compiler, do not modify

package ee.cyber.cdoc20.fbs.recipients;

@SuppressWarnings("unused")
public final class ServerDetailsUnion {
  private ServerDetailsUnion() { }
  public static final byte NONE = 0;
  public static final byte ServerEccDetails = 1;
  public static final byte ServerRsaDetails = 2;

  public static final String[] names = { "NONE", "ServerEccDetails", "ServerRsaDetails", };

  public static String name(int e) { return names[e]; }
}

