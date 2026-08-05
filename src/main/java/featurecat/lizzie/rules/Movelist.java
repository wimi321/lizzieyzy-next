package featurecat.lizzie.rules;

import java.util.ArrayList;

public class Movelist {
  public boolean ispass;
  public int x;
  public int y;
  public int movenum;
  public boolean isblack;

  public static ArrayList<Movelist> copyList(ArrayList<Movelist> source) {
    if (source == null) {
      return new ArrayList<>();
    }
    ArrayList<Movelist> copy = new ArrayList<>(source.size());
    for (Movelist move : source) {
      if (move == null) {
        copy.add(null);
        continue;
      }
      Movelist copied = new Movelist();
      copied.ispass = move.ispass;
      copied.x = move.x;
      copied.y = move.y;
      copied.movenum = move.movenum;
      copied.isblack = move.isblack;
      copy.add(copied);
    }
    return copy;
  }
}
