package org.batfish.symbolic.smt;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Model;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.batfish.common.BatfishException;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Flow;
import org.batfish.datamodel.FlowHistory;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.smt.EnvironmentType;
import org.batfish.datamodel.questions.smt.HeaderLocationQuestion;
import org.batfish.datamodel.questions.smt.HeaderQuestion;
import org.batfish.symbolic.CommunityVar;
import org.batfish.symbolic.Graph;
import org.batfish.symbolic.GraphEdge;
import org.batfish.symbolic.Protocol;
import org.batfish.symbolic.abstraction.Abstraction;
import org.batfish.symbolic.abstraction.EquivalenceClass;
import org.batfish.symbolic.answers.SmtDeterminismAnswerElement;
import org.batfish.symbolic.answers.SmtManyAnswerElement;
import org.batfish.symbolic.answers.SmtOneAnswerElement;
import org.batfish.symbolic.answers.SmtReachabilityAnswerElement;
import org.batfish.symbolic.collections.Table2;
import org.batfish.symbolic.utils.PathRegexes;
import org.batfish.symbolic.utils.PatternUtils;
import org.batfish.symbolic.utils.TriFunction;
import org.batfish.symbolic.utils.Tuple;

/**
 * A collection of functions to checks if various properties hold in the network. The general flow
 * is to create a new encoder object for the network, instrument additional properties on top of the
 * model, and then assert the negation of the property of interest.
 *
 * @author Ryan Beckett
 */
public class PropertyChecker {

  /*
   * Compute the forwarding behavior for the network. This adds no additional
   * constraints on top of the base network encoding. Forwarding will be
   * determined only for a particular network environment, failure scenario,
   * and data plane packet.
   */
  public static AnswerElement computeForwarding(IBatfish batfish, HeaderQuestion q) {
    Encoder encoder = new Encoder(batfish, q);
    encoder.computeEncoding();
    addEnvironmentConstraints(encoder, q.getBaseEnvironmentType());
    VerificationResult result = encoder.verify().getFirst();
    // result.debug(encoder.getMainSlice(), true, "0_R0_OSPF_IMPORT_Serial0_metric");
    return new SmtOneAnswerElement(result);
  }

  /*
   * Find the collection of relevant destination interfaces
   */
  private static Set<GraphEdge> findFinalInterfaces(Graph g, PathRegexes p) {
    Set<GraphEdge> edges = new HashSet<>();
    edges.addAll(PatternUtils.findMatchingEdges(g, p));
    return edges;
  }

  /*
   * From the destination interfaces, infer the relevant headerspace.
   * E.g., what range of destination IP is interesting for the query
   */
  private static void inferDestinationHeaderSpace(
      Graph g, Collection<GraphEdge> destPorts, HeaderLocationQuestion q) {
    // Infer relevant destination IP headerspace from interfaces
    if (q.getHeaderSpace().getDstIps().isEmpty()) {
      for (GraphEdge ge : destPorts) {
        // If there is an external interface, then
        // it can be any prefix, so we leave it unconstrained
        if (g.getEbgpNeighbors().containsKey(ge)) {
          q.getHeaderSpace().getDstIps().clear();
          q.getHeaderSpace().getNotDstIps().clear();
          break;
        }
        // If we don't know what is on the other end
        if (ge.getPeer() == null) {
          Prefix pfx = ge.getStart().getPrefix().getNetworkPrefix();
          IpWildcard dst = new IpWildcard(pfx);
          q.getHeaderSpace().getDstIps().add(dst);
        } else {
          // If host, add the subnet but not the neighbor's address
          if (g.isHost(ge.getRouter())) {
            Prefix pfx = ge.getStart().getPrefix().getNetworkPrefix();
            IpWildcard dst = new IpWildcard(pfx);
            q.getHeaderSpace().getDstIps().add(dst);
            Ip ip = ge.getEnd().getPrefix().getAddress();
            IpWildcard dst2 = new IpWildcard(ip);
            q.getHeaderSpace().getNotDstIps().add(dst2);
          } else {
            // Otherwise, we add the exact address
            Ip ip = ge.getStart().getPrefix().getAddress();
            IpWildcard dst = new IpWildcard(ip);
            q.getHeaderSpace().getDstIps().add(dst);
          }
        }
      }
    }
  }

  /*
   * Constraint that encodes if two symbolic records are equal
   */
  private static BoolExpr equal(Encoder e, Configuration conf, SymbolicRoute r1, SymbolicRoute r2) {
    EncoderSlice main = e.getMainSlice();
    BoolExpr eq = main.equal(conf, Protocol.CONNECTED, r1, r2, null, true);
    BoolExpr samePermitted = e.mkEq(r1.getPermitted(), r2.getPermitted());
    return e.mkAnd(eq, samePermitted);
  }

  /*
   * Find the set of edges that the solver can consider to fail
   */
  private static Set<GraphEdge> failLinkSet(Graph g, HeaderLocationQuestion q) {
    Pattern p1 = Pattern.compile(q.getFailNode1Regex());
    Pattern p2 = Pattern.compile(q.getFailNode2Regex());
    Pattern p3 = Pattern.compile(q.getNotFailNode1Regex());
    Pattern p4 = Pattern.compile(q.getNotFailNode2Regex());
    Set<GraphEdge> failChoices = PatternUtils.findMatchingEdges(g, p1, p2);
    Set<GraphEdge> failChoices2 = PatternUtils.findMatchingEdges(g, p2, p1);
    Set<GraphEdge> notFailChoices = PatternUtils.findMatchingEdges(g, p3, p4);
    Set<GraphEdge> notFailChoices2 = PatternUtils.findMatchingEdges(g, p4, p3);
    failChoices.addAll(failChoices2);
    failChoices.removeAll(notFailChoices);
    failChoices.removeAll(notFailChoices2);
    return failChoices;
  }

  /*
   * Creates a boolean expression that relates the environments of
   * two separate network copies.
   */
  private static BoolExpr relateEnvironments(Encoder enc1, Encoder enc2) {
    // create a map for enc2 to lookup a related environment variable from enc
    Table2<GraphEdge, EdgeType, SymbolicRoute> relatedEnv = new Table2<>();
    for (Entry<LogicalEdge, SymbolicRoute> entry :
        enc2.getMainSlice().getLogicalGraph().getEnvironmentVars().entrySet()) {
      LogicalEdge lge = entry.getKey();
      SymbolicRoute r = entry.getValue();
      relatedEnv.put(lge.getEdge(), lge.getEdgeType(), r);
    }

    BoolExpr related = enc1.mkTrue();

    // relate environments if necessary
    Map<LogicalEdge, SymbolicRoute> map =
        enc1.getMainSlice().getLogicalGraph().getEnvironmentVars();
    for (Map.Entry<LogicalEdge, SymbolicRoute> entry : map.entrySet()) {
      LogicalEdge le = entry.getKey();
      SymbolicRoute r1 = entry.getValue();
      String router = le.getEdge().getRouter();
      Configuration conf = enc1.getMainSlice().getGraph().getConfigurations().get(router);

      // Lookup the same environment variable in the other copy
      // The copy will have a different name but the same edge and type
      SymbolicRoute r2 = relatedEnv.get(le.getEdge(), le.getEdgeType());
      assert r2 != null;
      BoolExpr x = equal(enc1, conf, r1, r2);
      related = enc1.mkAnd(related, x);
    }
    return related;
  }

  /*
   * Creates a boolean expression that relates the environments of
   * two separate network copies.
   */
  private static BoolExpr relateFailures(Encoder enc1, Encoder enc2) {
    BoolExpr related = enc1.mkTrue();
    for (GraphEdge ge : enc1.getMainSlice().getGraph().getAllRealEdges()) {
      ArithExpr a1 = enc1.getSymbolicFailures().getFailedVariable(ge);
      ArithExpr a2 = enc2.getSymbolicFailures().getFailedVariable(ge);
      assert a1 != null;
      assert a2 != null;
      related = enc1.mkEq(a1, a2);
    }
    return related;
  }

  /*
   * Relate the two packets from different network copies
   */
  private static BoolExpr relatePackets(Encoder enc1, Encoder enc2) {
    SymbolicPacket p1 = enc1.getMainSlice().getSymbolicPacket();
    SymbolicPacket p2 = enc2.getMainSlice().getSymbolicPacket();
    return p1.mkEqual(p2);
  }

  /*
   * Adds additional constraints that certain edges should not be failed to avoid
   * trivial false positives.
   */
  private static void addFailureConstraints(
      Encoder enc, Set<GraphEdge> dstPorts, Set<GraphEdge> failSet) {
    Graph graph = enc.getMainSlice().getGraph();
    for (List<GraphEdge> edges : graph.getEdgeMap().values()) {
      for (GraphEdge ge : edges) {
        ArithExpr f = enc.getSymbolicFailures().getFailedVariable(ge);
        assert f != null;
        if (!failSet.contains(ge)) {
          enc.add(enc.mkEq(f, enc.mkInt(0)));
        } else if (dstPorts.contains(ge)) {
          // Don't fail an interface if it is for the destination ip we are considering
          // Otherwise, any failure can trivially make equivalence false
          Prefix pfx = ge.getStart().getPrefix();
          BitVecExpr dstIp = enc.getMainSlice().getSymbolicPacket().getDstIp();
          BoolExpr relevant = enc.getMainSlice().isRelevantFor(pfx, dstIp);
          BoolExpr notFailed = enc.mkEq(f, enc.mkInt(0));
          enc.add(enc.mkImplies(relevant, notFailed));
        }
      }
    }
  }

  /*
   * Add constraints on the environment
   */
  private static void addEnvironmentConstraints(Encoder enc, EnvironmentType t) {
    LogicalGraph lg = enc.getMainSlice().getLogicalGraph();
    Context ctx = enc.getCtx();
    switch (t) {
      case ANY:
        break;
      case NONE:
        for (SymbolicRoute vars : lg.getEnvironmentVars().values()) {
          enc.add(ctx.mkNot(vars.getPermitted()));
        }
        break;
      case SANE:
        for (SymbolicRoute vars : lg.getEnvironmentVars().values()) {
          enc.add(ctx.mkLe(vars.getMetric(), ctx.mkInt(50)));
        }
        break;
      default:
        break;
    }
  }

  /*
   * Returns an iterator that lazily generates new Equivalence classes to verify.
   * If the abstraction option is enabled, will create abstract networks on-the-fly.
   */
  private static Iterator<EquivalenceClass> findAllEquivalenceClasses(
      IBatfish batfish, HeaderQuestion q, Graph graph) {
    if (q.getUseAbstraction()) {
      Abstraction abs = Abstraction.create(batfish, new ArrayList<>(), q.getHeaderSpace());
      return abs.iterator();
    }
    List<EquivalenceClass> singleEc = new ArrayList<>();
    EquivalenceClass ec = new EquivalenceClass(q.getHeaderSpace(), graph, null);
    singleEc.add(ec);
    return singleEc.iterator();
  }

  /*
   * Apply mapping from concrete to abstract nodes
   */
  private static Set<String> mapNodes(EquivalenceClass ec, List<String> concreteNodes) {
    if (ec.getAbstraction() == null) {
      return new HashSet<>(concreteNodes);
    }
    Set<String> abstractNodes = new HashSet<>();
    for (String c : concreteNodes) {
      String a = ec.getAbstraction().get(c);
      abstractNodes.add(a);
    }
    return abstractNodes;
  }

  private static AnswerElement computeProperty(
      IBatfish batfish,
      HeaderLocationQuestion q,
      TriFunction<Encoder, Set<String>, Set<GraphEdge>, Map<String, BoolExpr>> instrument,
      Function<VerifyParam, AnswerElement> answer) {

    PathRegexes p = new PathRegexes(q);
    long l = System.currentTimeMillis();
    Graph graph = new Graph(batfish);
    Set<GraphEdge> destPorts = findFinalInterfaces(graph, p);
    List<String> sourceRouters = PatternUtils.findMatchingSourceNodes(graph, p);

    if (destPorts.isEmpty()) {
      throw new BatfishException("Set of valid destination interfaces is empty");
    }
    if (sourceRouters.isEmpty()) {
      throw new BatfishException("Set of valid ingress nodes is empty");
    }

    VerificationResult res = null;
    inferDestinationHeaderSpace(graph, destPorts, q);
    Set<GraphEdge> failOptions = failLinkSet(graph, q);
    Iterator<EquivalenceClass> it = findAllEquivalenceClasses(batfish, q, graph);

    // long start = System.currentTimeMillis();
    while (it.hasNext()) {
      EquivalenceClass ec = it.next();
      graph = ec.getGraph();
      Set<String> srcRouters = mapNodes(ec, sourceRouters);

      Encoder enc = new Encoder(graph, q);
      long l1 = System.currentTimeMillis();
      enc.computeEncoding();
      System.out.println("Encoding1 : " + (System.currentTimeMillis() - l1));

      // Add environment constraints for base case
      if (q.getDiffType() != null) {
        if (q.getEnvDiff()) {
          addEnvironmentConstraints(enc, q.getDeltaEnvironmentType());
        }
      } else {
        addEnvironmentConstraints(enc, q.getBaseEnvironmentType());
      }

      Map<String, BoolExpr> prop = instrument.apply(enc, srcRouters, destPorts);

      // If this is a equivalence query, we create a second copy of the network
      Encoder enc2 = null;
      Map<String, BoolExpr> prop2 = null;

      if (q.getDiffType() != null) {
        HeaderLocationQuestion q2 = new HeaderLocationQuestion(q);
        q2.setFailures(0);
        enc2 = new Encoder(enc, graph, q2);
        long l2 = System.currentTimeMillis();
        enc2.computeEncoding();
        System.out.println("Encoding2 : " + (System.currentTimeMillis() - l2));
      }

      if (q.getDiffType() != null) {
        assert (enc2 != null);
        // create a map for enc2 to lookup a related environment variable from enc
        Table2<GraphEdge, EdgeType, SymbolicRoute> relatedEnv = new Table2<>();
        enc2.getMainSlice()
            .getLogicalGraph()
            .getEnvironmentVars()
            .forEach((lge, r) -> relatedEnv.put(lge.getEdge(), lge.getEdgeType(), r));

        BoolExpr related = enc.mkTrue();
        addEnvironmentConstraints(enc2, q.getBaseEnvironmentType());

        if (!q.getEnvDiff()) {
          related = relateEnvironments(enc, enc2);
        }

        prop2 = instrument.apply(enc2, srcRouters, destPorts);

        // Add diff constraints
        BoolExpr required = enc.mkTrue();
        for (String source : srcRouters) {
          BoolExpr sourceProp1 = prop.get(source);
          BoolExpr sourceProp2 = prop2.get(source);
          BoolExpr val;
          switch (q.getDiffType()) {
            case INCREASED:
              val = enc.mkImplies(sourceProp1, sourceProp2);
              break;
            case REDUCED:
              val = enc.mkImplies(sourceProp2, sourceProp1);
              break;
            case ANY:
              val = enc.mkEq(sourceProp1, sourceProp2);
              break;
            default:
              throw new BatfishException("Missing case: " + q.getDiffType());
          }
          required = enc.mkAnd(required, val);
        }

        related = enc.mkAnd(related, relatePackets(enc, enc2));
        enc.add(related);
        enc.add(enc.mkNot(required));

      } else {
        BoolExpr allProp = enc.mkTrue();
        for (String router : srcRouters) {
          BoolExpr r = prop.get(router);
          allProp = enc.mkAnd(allProp, r);
        }
        enc.add(enc.mkNot(allProp));
      }

      addFailureConstraints(enc, destPorts, failOptions);

      //long startVerify = System.currentTimeMillis();
      Tuple<VerificationResult, Model> result = enc.verify();
      //System.out.println("Verification time: " + (System.currentTimeMillis() - startVerify));

      res = result.getFirst();
      Model model = result.getSecond();

      if (res.isVerified()) {
        continue;
      }

      VerifyParam vp = new VerifyParam(res, model, sourceRouters, enc, enc2, prop, prop2);
      return answer.apply(vp);
    }

    System.out.println("Total time: " + (System.currentTimeMillis() - l));

    VerifyParam vp = new VerifyParam(res, null, null, null, null, null, null);
    return answer.apply(vp);
  }

  /*
   * Compute if a collection of source routers can reach a collection of destination
   * ports. This is broken up into multiple queries, one for each destination port.
   */
  public static AnswerElement computeReachability(IBatfish batfish, HeaderLocationQuestion q) {
    return computeProperty(
        batfish,
        q,
        (enc, srcRouters, destPorts) -> {
          PropertyAdder pa = new PropertyAdder(enc.getMainSlice());
          return pa.instrumentReachability(destPorts);
        },
        (vp) -> {
          if (vp.getResult().isVerified()) {
            return new SmtReachabilityAnswerElement(vp.getResult(), new FlowHistory());
          } else {
            FlowHistory fh;
            CounterExample ce = new CounterExample(vp.getModel());
            String testrigName = batfish.getTestrigName();
            if (q.getDiffType() != null) {
              fh =
                  ce.buildFlowDiffCounterExample(
                      testrigName,
                      vp.getSourceRouters(),
                      vp.getEncoder1(),
                      vp.getEncoder2(),
                      vp.getProp1(),
                      vp.getProp2());
            } else {
              fh =
                  ce.buildFlowCounterExample(
                      testrigName, vp.getSourceRouters(), vp.getEncoder1(), vp.getProp1());
            }
            return new SmtReachabilityAnswerElement(vp.getResult(), fh);
          }
        });
  }

  /*
   * Check if there exist multiple stable solutions to the netowrk.
   * If so, reports the forwarding differences between the two cases.
   */
  public static AnswerElement computeDeterminism(IBatfish batfish, HeaderQuestion q) {
    Graph graph = new Graph(batfish);
    Encoder enc1 = new Encoder(graph, q);
    Encoder enc2 = new Encoder(enc1, graph, q);
    enc1.computeEncoding();
    enc2.computeEncoding();
    addEnvironmentConstraints(enc1, q.getBaseEnvironmentType());

    BoolExpr relatedFailures = relateFailures(enc1, enc2);
    BoolExpr relatedEnvs = relateEnvironments(enc1, enc2);
    BoolExpr relatedPkts = relatePackets(enc1, enc2);
    BoolExpr related = enc1.mkAnd(relatedFailures, relatedEnvs, relatedPkts);
    BoolExpr required = enc1.mkTrue();
    for (GraphEdge ge : graph.getAllRealEdges()) {
      SymbolicDecisions d1 = enc1.getMainSlice().getSymbolicDecisions();
      SymbolicDecisions d2 = enc2.getMainSlice().getSymbolicDecisions();
      BoolExpr dataFwd1 = d1.getDataForwarding().get(ge.getRouter(), ge);
      BoolExpr dataFwd2 = d2.getDataForwarding().get(ge.getRouter(), ge);
      assert dataFwd1 != null;
      assert dataFwd2 != null;
      required = enc1.mkAnd(required, enc1.mkEq(dataFwd1, dataFwd2));
    }

    enc1.add(related);
    enc1.add(enc1.mkNot(required));

    Tuple<VerificationResult, Model> tup = enc1.verify();
    VerificationResult res = tup.getFirst();
    Model model = tup.getSecond();

    SortedSet<String> case1 = null;
    SortedSet<String> case2 = null;
    Flow flow = null;
    CounterExample ce = new CounterExample(model);
    if (!res.isVerified()) {
      case1 = new TreeSet<>();
      case2 = new TreeSet<>();
      flow = ce.buildFlow(enc1.getMainSlice().getSymbolicPacket(), "(none)");
      for (GraphEdge ge : graph.getAllRealEdges()) {
        SymbolicDecisions d1 = enc1.getMainSlice().getSymbolicDecisions();
        SymbolicDecisions d2 = enc2.getMainSlice().getSymbolicDecisions();
        BoolExpr dataFwd1 = d1.getDataForwarding().get(ge.getRouter(), ge);
        BoolExpr dataFwd2 = d2.getDataForwarding().get(ge.getRouter(), ge);
        assert dataFwd1 != null;
        assert dataFwd2 != null;
        boolean b1 = ce.boolVal(dataFwd1);
        boolean b2 = ce.boolVal(dataFwd2);
        if (b1 != b2) {
          if (b1) {
            String route = ce.buildRoute(enc1.getMainSlice(), ge);
            String msg = ge + " -- " + route;
            case1.add(msg);
          }
          if (b2) {
            String route = ce.buildRoute(enc2.getMainSlice(), ge);
            String msg = ge + " -- " + route;
            case2.add(msg);
          }
        }
      }
    }

    return new SmtDeterminismAnswerElement(res, flow, case1, case2);
  }

  /*
   * Compute whether the path length will always be bounded by a constant k
   * for a collection of source routers to any of a number of destination ports.
   */
  public static AnswerElement computeBoundedLength(
      IBatfish batfish, HeaderLocationQuestion q, int k) {

    return computeProperty(
        batfish,
        q,
        (enc, srcRouters, destPorts) -> {
          ArithExpr bound = enc.mkInt(k);
          PropertyAdder pa = new PropertyAdder(enc.getMainSlice());
          Map<String, ArithExpr> lenVars = pa.instrumentPathLength(destPorts);
          Map<String, BoolExpr> boundVars = new HashMap<>();
          lenVars.forEach((n, ae) -> boundVars.put(n, enc.mkLe(ae, bound)));
          return boundVars;
        },
        (vp) -> new SmtOneAnswerElement(vp.getResult()));
  }

  /*
   * Computes whether a collection of source routers will always have
   * equal path length to destination port(s).
   */
  public static AnswerElement computeEqualLength(IBatfish batfish, HeaderLocationQuestion q) {
    return computeProperty(
        batfish,
        q,
        (enc, srcRouters, destPorts) -> {
          PropertyAdder pa = new PropertyAdder(enc.getMainSlice());
          Map<String, ArithExpr> lenVars = pa.instrumentPathLength(destPorts);
          Map<String, BoolExpr> eqVars = new HashMap<>();
          List<Expr> lens = new ArrayList<>();
          for (String router : srcRouters) {
            lens.add(lenVars.get(router));
          }
          BoolExpr allEqual = PropertyAdder.allEqual(enc.getCtx(), lens);
          enc.add(enc.mkNot(allEqual));
          lenVars.forEach((name, ae) -> eqVars.put(name, allEqual));
          return eqVars;
        },
        (vp) -> new SmtOneAnswerElement(vp.getResult()));
  }

  /*
   * Computes whether load balancing for each source node in a collection is
   * within some threshold k of the each other.
   */
  public static AnswerElement computeLoadBalance(
      IBatfish batfish, HeaderLocationQuestion q, int k) {

    PathRegexes p = new PathRegexes(q);

    Graph graph = new Graph(batfish);
    List<GraphEdge> destinationPorts = PatternUtils.findMatchingEdges(graph, p);
    List<String> sourceRouters = PatternUtils.findMatchingSourceNodes(graph, p);
    Map<String, List<String>> peerRouters = new HashMap<>();
    SortedMap<String, VerificationResult> result = new TreeMap<>();

    List<String> pRouters = PatternUtils.findMatchingSourceNodes(graph, p);

    // TODO: refactor this out separately
    for (String router : sourceRouters) {
      List<String> list = new ArrayList<>();
      peerRouters.put(router, list);
      Set<String> neighbors = graph.getNeighbors().get(router);
      for (String peer : pRouters) {
        if (neighbors.contains(peer)) {
          list.add(peer);
        }
      }
    }

    for (GraphEdge ge : destinationPorts) {
      // Add the interface destination
      boolean addedDestination = false;
      if (q.getHeaderSpace().getDstIps().isEmpty()) {
        addedDestination = true;
        Prefix destination = ge.getStart().getPrefix();
        IpWildcard dst = new IpWildcard(destination);
        q.getHeaderSpace().getDstIps().add(dst);
      }

      Encoder enc = new Encoder(graph, q);
      enc.computeEncoding();

      EncoderSlice slice = enc.getMainSlice();

      PropertyAdder pa = new PropertyAdder(slice);
      Map<String, ArithExpr> loadVars = pa.instrumentLoad(ge);

      Context ctx = enc.getCtx();

      // TODO: add threshold
      // All routers bounded by a particular length
      List<Expr> peerLoads = new ArrayList<>();
      for (Entry<String, List<String>> entry : peerRouters.entrySet()) {
        String router = entry.getKey();
        List<String> allPeers = entry.getValue();
        for (String peer : allPeers) {
          peerLoads.add(loadVars.get(peer));
        }
      }

      BoolExpr evenLoads = PropertyAdder.allEqual(ctx, peerLoads);
      enc.add(ctx.mkNot(evenLoads));

      VerificationResult res = enc.verify().getFirst();
      result.put(ge.getRouter() + "," + ge.getStart().getName(), res);

      if (addedDestination) {
        q.getHeaderSpace().getDstIps().clear();
      }
    }

    return new SmtManyAnswerElement(result);
  }

  /*
   * Compute if there can ever be a black hole for routers that are
   * not at the edge of the network. This is almost certainly a bug.
   */
  public static AnswerElement computeBlackHole(IBatfish batfish, HeaderQuestion q) {
    Graph graph = new Graph(batfish);
    Encoder enc = new Encoder(graph, q);
    enc.computeEncoding();
    Context ctx = enc.getCtx();
    EncoderSlice slice = enc.getMainSlice();

    // Collect routers that have no host/environment edge
    List<String> toCheck = new ArrayList<>();
    for (Entry<String, List<GraphEdge>> entry : graph.getEdgeMap().entrySet()) {
      String router = entry.getKey();
      List<GraphEdge> edges = entry.getValue();
      boolean check = true;
      for (GraphEdge edge : edges) {
        if (edge.getEnd() == null) {
          check = false;
          break;
        }
      }
      if (check) {
        toCheck.add(router);
      }
    }

    // Ensure the router never receives traffic and then drops the traffic
    BoolExpr someBlackHole = ctx.mkBool(false);
    for (String router : toCheck) {
      Map<GraphEdge, BoolExpr> edges = slice.getSymbolicDecisions().getDataForwarding().get(router);
      BoolExpr doesNotFwd = ctx.mkBool(true);
      for (Map.Entry<GraphEdge, BoolExpr> entry : edges.entrySet()) {
        BoolExpr dataFwd = entry.getValue();
        doesNotFwd = ctx.mkAnd(doesNotFwd, ctx.mkNot(dataFwd));
      }
      BoolExpr isFwdTo = ctx.mkBool(false);
      Set<String> neighbors = graph.getNeighbors().get(router);
      for (String n : neighbors) {
        for (Map.Entry<GraphEdge, BoolExpr> entry :
            slice.getSymbolicDecisions().getDataForwarding().get(n).entrySet()) {
          GraphEdge ge = entry.getKey();
          BoolExpr fwd = entry.getValue();
          if (router.equals(ge.getPeer())) {
            isFwdTo = ctx.mkOr(isFwdTo, fwd);
          }
        }
      }
      someBlackHole = ctx.mkOr(someBlackHole, ctx.mkAnd(isFwdTo, doesNotFwd));
    }

    enc.add(someBlackHole);
    VerificationResult result = enc.verify().getFirst();
    return new SmtOneAnswerElement(result);
  }

  /*
   * Computes whether or not two routers are equivalent.
   * To be equivalent, each router must have identical intefaces.
   *
   * We then relate the environments on each interface for each router
   * so that they are required to be equal.
   *
   * We finally check that their forwarding decisions and exported messages
   * will be equal given their equal inputs.
   */
  public static AnswerElement computeLocalConsistency(
      IBatfish batfish, Pattern n, boolean strict, boolean fullModel) {
    Graph graph = new Graph(batfish);
    List<String> routers = PatternUtils.findMatchingNodes(graph, n, Pattern.compile(""));

    HeaderQuestion q = new HeaderQuestion();
    q.setFullModel(fullModel);
    q.setFailures(0);
    q.setBaseEnvironmentType(EnvironmentType.ANY);

    Collections.sort(routers);
    SortedMap<String, VerificationResult> result = new TreeMap<>();

    int len = routers.size();
    if (len <= 1) {
      return new SmtManyAnswerElement(new TreeMap<>());
    }

    for (int i = 0; i < len - 1; i++) {
      String r1 = routers.get(i);
      String r2 = routers.get(i + 1);

      // TODO: reorder to encode after checking if we can compare them

      // Create transfer function for router 1
      Set<String> toModel1 = new TreeSet<>();
      toModel1.add(r1);
      Graph g1 = new Graph(batfish, null, toModel1);
      Encoder e1 = new Encoder(g1, q);
      e1.computeEncoding();

      Context ctx = e1.getCtx();

      // Create transfer function for router 2
      Set<String> toModel2 = new TreeSet<>();
      toModel2.add(r2);
      Graph g2 = new Graph(batfish, null, toModel2);
      Encoder e2 = new Encoder(e1, g2);
      e2.computeEncoding();

      EncoderSlice slice1 = e1.getMainSlice();
      EncoderSlice slice2 = e2.getMainSlice();

      // Ensure that the two routers have the same interfaces for comparison
      Pattern p = Pattern.compile(".*");
      Pattern neg = Pattern.compile("");
      List<GraphEdge> edges1 = PatternUtils.findMatchingEdges(g1, p, neg, p, neg);
      List<GraphEdge> edges2 = PatternUtils.findMatchingEdges(g2, p, neg, p, neg);
      Set<String> ifaces1 = interfaces(edges1);
      Set<String> ifaces2 = interfaces(edges2);

      if (!(ifaces1.containsAll(ifaces2) && ifaces2.containsAll(ifaces1))) {
        String msg = String.format("Routers %s and %s have different interfaces", r1, r2);
        System.out.println(msg);
        return new SmtManyAnswerElement(new TreeMap<>());
      }

      // TODO: check running same protocols?

      // Map<String, Map<Protocol, Map<String, EnumMap<EdgeType, LogicalEdge>>>>
      //        lgeMap1 = logicalEdgeMap(e1);
      Map<String, Map<Protocol, Map<String, EnumMap<EdgeType, LogicalEdge>>>> lgeMap2 =
          logicalEdgeMap(slice2);

      BoolExpr equalEnvs = ctx.mkBool(true);
      BoolExpr equalOutputs = ctx.mkBool(true);
      BoolExpr equalIncomingAcls = ctx.mkBool(true);

      Configuration conf1 = g1.getConfigurations().get(r1);
      Configuration conf2 = g2.getConfigurations().get(r2);

      // Set environments equal
      Set<String> communities = new HashSet<>();

      Set<SymbolicRoute> envRecords = new HashSet<>();

      for (Protocol proto1 : slice1.getProtocols().get(r1)) {
        for (ArrayList<LogicalEdge> es :
            slice1.getLogicalGraph().getLogicalEdges().get(r1).get(proto1)) {
          for (LogicalEdge lge1 : es) {

            String ifaceName = lge1.getEdge().getStart().getName();

            LogicalEdge lge2 = lgeMap2.get(r2).get(proto1).get(ifaceName).get(lge1.getEdgeType());

            if (lge1.getEdgeType() == EdgeType.IMPORT) {

              SymbolicRoute vars1 = slice1.getLogicalGraph().getEnvironmentVars().get(lge1);
              SymbolicRoute vars2 = slice2.getLogicalGraph().getEnvironmentVars().get(lge2);

              BoolExpr aclIn1 = slice1.getIncomingAcls().get(lge1.getEdge());
              BoolExpr aclIn2 = slice2.getIncomingAcls().get(lge2.getEdge());

              if (aclIn1 == null) {
                aclIn1 = ctx.mkBool(true);
              }
              if (aclIn2 == null) {
                aclIn2 = ctx.mkBool(true);
              }

              equalIncomingAcls = ctx.mkAnd(equalIncomingAcls, ctx.mkEq(aclIn1, aclIn2));

              boolean hasEnv1 = (vars1 != null);
              boolean hasEnv2 = (vars2 != null);

              if (hasEnv1 && hasEnv2) {
                BoolExpr samePermitted = ctx.mkEq(vars1.getPermitted(), vars2.getPermitted());

                // Set communities equal
                BoolExpr equalComms = e1.mkTrue();
                for (Map.Entry<CommunityVar, BoolExpr> entry : vars1.getCommunities().entrySet()) {
                  CommunityVar cvar = entry.getKey();
                  BoolExpr ce1 = entry.getValue();
                  BoolExpr ce2 = vars2.getCommunities().get(cvar);
                  if (ce2 != null) {
                    equalComms = e1.mkAnd(equalComms, e1.mkEq(ce1, ce2));
                  }
                }

                // Set communities belonging to one but not the other
                // off, but give a warning of the difference
                BoolExpr unsetComms = e1.mkTrue();

                for (Map.Entry<CommunityVar, BoolExpr> entry : vars1.getCommunities().entrySet()) {
                  CommunityVar cvar = entry.getKey();
                  BoolExpr ce1 = entry.getValue();
                  BoolExpr ce2 = vars2.getCommunities().get(cvar);
                  if (ce2 == null) {

                    if (!communities.contains(cvar.getValue())) {
                      communities.add(cvar.getValue());
                      /* String msg =
                       String.format(
                           "Warning: community %s found for router %s but not %s.",
                           cvar.getValue(), conf1.getEnvName(), conf2.getEnvName());
                      System.out.println(msg); */
                    }
                    unsetComms = e1.mkAnd(unsetComms, e1.mkNot(ce1));
                  }
                }

                // Do the same thing for communities missing from the other side
                for (Map.Entry<CommunityVar, BoolExpr> entry : vars2.getCommunities().entrySet()) {
                  CommunityVar cvar = entry.getKey();
                  BoolExpr ce2 = entry.getValue();
                  BoolExpr ce1 = vars1.getCommunities().get(cvar);
                  if (ce1 == null) {
                    if (!communities.contains(cvar.getValue())) {
                      communities.add(cvar.getValue());
                      /* String msg =
                       String.format(
                           "Warning: community %s found for router %s but not %s.",
                           cvar.getValue(), conf2.getEnvName(), conf1.getEnvName());
                      System.out.println(msg); */
                    }
                    unsetComms = e1.mkAnd(unsetComms, e1.mkNot(ce2));
                  }
                }

                envRecords.add(vars1);

                BoolExpr equalVars = slice1.equal(conf1, proto1, vars1, vars2, lge1, true);
                equalEnvs = ctx.mkAnd(equalEnvs, unsetComms, samePermitted, equalVars, equalComms);

                // System.out.println("Unset communities: ");
                // System.out.println(unsetComms);

              } else if (hasEnv1 || hasEnv2) {
                System.out.println("Edge1: " + lge1);
                System.out.println("Edge2: " + lge2);
                throw new BatfishException("one had environment");
              }

            } else {

              SymbolicRoute out1 = lge1.getSymbolicRecord();
              SymbolicRoute out2 = lge2.getSymbolicRecord();

              equalOutputs =
                  ctx.mkAnd(equalOutputs, slice1.equal(conf1, proto1, out1, out2, lge1, false));
            }
          }
        }
      }

      // Ensure that there is only one active environment message if we want to
      // check the stronger version of local equivalence
      if (strict) {
        for (SymbolicRoute env1 : envRecords) {
          for (SymbolicRoute env2 : envRecords) {
            if (!env1.equals(env2)) {
              BoolExpr c = e2.mkImplies(env1.getPermitted(), e2.mkNot(env2.getPermitted()));
              e2.add(c);
            }
          }
        }
      }

      // TODO: check both have same environment vars (e.g., screw up configuring peer connection)

      // Create assumptions
      BoolExpr validDest;
      validDest = ignoredDestinations(ctx, slice1, r1, conf1);
      validDest = ctx.mkAnd(validDest, ignoredDestinations(ctx, slice2, r2, conf2));
      SymbolicPacket p1 = slice1.getSymbolicPacket();
      SymbolicPacket p2 = slice2.getSymbolicPacket();
      BoolExpr equalPackets = p1.mkEqual(p2);
      BoolExpr assumptions = ctx.mkAnd(equalEnvs, equalPackets, validDest);

      // Create the requirements

      // Best choices should be the same
      BoolExpr required;
      if (strict) {
        SymbolicRoute best1 =
            e1.getMainSlice().getSymbolicDecisions().getBestNeighbor().get(conf1.getName());
        SymbolicRoute best2 =
            e2.getMainSlice().getSymbolicDecisions().getBestNeighbor().get(conf2.getName());
        // Just pick some protocol for defaults, shouldn't matter for best choice
        required = equal(e2, conf2, best1, best2);
      } else {
        // Forwarding decisions should be the sames
        Map<String, GraphEdge> geMap2 = interfaceMap(edges2);
        BoolExpr sameForwarding = ctx.mkBool(true);
        for (GraphEdge ge1 : edges1) {
          GraphEdge ge2 = geMap2.get(ge1.getStart().getName());
          BoolExpr dataFwd1 = slice1.getSymbolicDecisions().getDataForwarding().get(r1, ge1);
          BoolExpr dataFwd2 = slice2.getSymbolicDecisions().getDataForwarding().get(r2, ge2);
          assert (dataFwd1 != null);
          assert (dataFwd2 != null);
          sameForwarding = ctx.mkAnd(sameForwarding, ctx.mkEq(dataFwd1, dataFwd2));
        }
        required = ctx.mkAnd(sameForwarding); // equalOutputs, equalIncomingAcls);
      }

      e2.add(assumptions);
      e2.add(ctx.mkNot(required));

      VerificationResult res = e2.verify().getFirst();
      String name = r1 + "<-->" + r2;
      result.put(name, res);
    }

    return new SmtManyAnswerElement(result);
  }

  /*
   * Get the interface names for a collection of edges
   */
  private static Set<String> interfaces(List<GraphEdge> edges) {
    Set<String> ifaces = new TreeSet<>();
    for (GraphEdge edge : edges) {
      ifaces.add(edge.getStart().getName());
    }
    return ifaces;
  }

  /*
   * Build the inverse map for each logical edge
   */
  private static Map<String, Map<Protocol, Map<String, EnumMap<EdgeType, LogicalEdge>>>>
      logicalEdgeMap(EncoderSlice enc) {

    Map<String, Map<Protocol, Map<String, EnumMap<EdgeType, LogicalEdge>>>> acc = new HashMap<>();
    enc.getLogicalGraph()
        .getLogicalEdges()
        .forEach(
            (router, map) -> {
              Map<Protocol, Map<String, EnumMap<EdgeType, LogicalEdge>>> mapAcc = new HashMap<>();
              acc.put(router, mapAcc);
              map.forEach(
                  (proto, edges) -> {
                    Map<String, EnumMap<EdgeType, LogicalEdge>> edgesMap = new HashMap<>();
                    mapAcc.put(proto, edgesMap);
                    for (ArrayList<LogicalEdge> xs : edges) {
                      for (LogicalEdge lge : xs) {
                        // Should have import since only connected to environment
                        String ifaceName = lge.getEdge().getStart().getName();
                        EnumMap<EdgeType, LogicalEdge> typeMap = edgesMap.get(ifaceName);
                        if (typeMap == null) {
                          EnumMap<EdgeType, LogicalEdge> m = new EnumMap<>(EdgeType.class);
                          m.put(lge.getEdgeType(), lge);
                          edgesMap.put(ifaceName, m);
                        } else {
                          typeMap.put(lge.getEdgeType(), lge);
                        }
                      }
                    }
                  });
            });
    return acc;
  }

  /*
   * Creates a boolean variable representing destinations we don't want
   * to consider due to local differences.
   */
  private static BoolExpr ignoredDestinations(
      Context ctx, EncoderSlice e1, String r1, Configuration conf1) {
    BoolExpr validDest = ctx.mkBool(true);
    for (Protocol proto1 : e1.getProtocols().get(r1)) {
      Set<Prefix> prefixes = Graph.getOriginatedNetworks(conf1, proto1);
      BoolExpr dest = e1.relevantOrigination(prefixes);
      validDest = ctx.mkAnd(validDest, ctx.mkNot(dest));
    }
    return validDest;
  }

  /*
   * Create a map from interface name to graph edge.
   */
  private static Map<String, GraphEdge> interfaceMap(List<GraphEdge> edges) {
    Map<String, GraphEdge> ifaceMap = new HashMap<>();
    for (GraphEdge edge : edges) {
      ifaceMap.put(edge.getStart().getName(), edge);
    }
    return ifaceMap;
  }

  /*
   * Computes multipath consistency, which ensures traffic that travels
   * multiple paths will be treated equivalently by each path
   * (i.e., dropped or accepted by each).
   */
  public static AnswerElement computeMultipathConsistency(
      IBatfish batfish, HeaderLocationQuestion q) {
    PathRegexes p = new PathRegexes(q);
    Graph graph = new Graph(batfish);
    Set<GraphEdge> destPorts = findFinalInterfaces(graph, p);
    inferDestinationHeaderSpace(graph, destPorts, q);

    Encoder enc = new Encoder(graph, q);
    enc.computeEncoding();
    EncoderSlice slice = enc.getMainSlice();

    PropertyAdder pa = new PropertyAdder(slice);
    Map<String, BoolExpr> reachableVars = pa.instrumentReachability(destPorts);

    BoolExpr acc = enc.mkFalse();
    for (Map.Entry<String, Configuration> entry : graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      BoolExpr reach = reachableVars.get(router);

      BoolExpr all = enc.mkTrue();
      for (GraphEdge edge : graph.getEdgeMap().get(router)) {
        BoolExpr dataFwd = slice.getForwardsAcross().get(router, edge);
        BoolExpr ctrFwd = slice.getSymbolicDecisions().getControlForwarding().get(router, edge);
        assert (ctrFwd != null);
        BoolExpr peerReach = enc.mkTrue();
        if (edge.getPeer() != null) {
          peerReach = reachableVars.get(edge.getPeer());
        }
        BoolExpr imp = enc.mkImplies(ctrFwd, enc.mkAnd(dataFwd, peerReach));

        all = enc.mkAnd(all, imp);
      }

      acc = enc.mkOr(acc, enc.mkNot(enc.mkImplies(reach, all)));
    }

    enc.add(acc);
    VerificationResult res = enc.verify().getFirst();
    return new SmtOneAnswerElement(res);
  }

  /*
   * Checks for routing loops in the network. For efficiency reasons,
   * we only check for loops with routers that use static routes since
   * these can override the usual loop-prevention mechanisms.
   */
  public static AnswerElement computeRoutingLoop(IBatfish batfish, HeaderQuestion q) {
    Graph graph = new Graph(batfish);

    // Collect all relevant destinations
    List<Prefix> prefixes = new ArrayList<>();
    graph
        .getStaticRoutes()
        .forEach(
            (router, ifaceName, srs) -> {
              for (StaticRoute sr : srs) {
                prefixes.add(sr.getNetwork());
              }
            });

    SortedSet<IpWildcard> pfxs = new TreeSet<>();
    for (Prefix prefix : prefixes) {
      pfxs.add(new IpWildcard(prefix));
    }
    q.getHeaderSpace().setDstIps(pfxs);

    // Collect all routers that use static routes as a
    // potential node along a loop
    List<String> routers = new ArrayList<>();
    for (Entry<String, Configuration> entry : graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      if (conf.getDefaultVrf().getStaticRoutes().size() > 0) {
        routers.add(router);
      }
    }

    Encoder enc = new Encoder(graph, q);
    enc.computeEncoding();
    Context ctx = enc.getCtx();

    EncoderSlice slice = enc.getMainSlice();

    PropertyAdder pa = new PropertyAdder(slice);

    BoolExpr someLoop = ctx.mkBool(false);
    for (String router : routers) {
      BoolExpr hasLoop = pa.instrumentLoop(router);
      someLoop = ctx.mkOr(someLoop, hasLoop);
    }
    enc.add(someLoop);

    VerificationResult result = enc.verify().getFirst();

    return new SmtOneAnswerElement(result);
  }



  private static class VerifyParam {

    private VerificationResult _result;

    private Model _model;

    private List<String> _sourceRouters;

    private Encoder _encoder1;

    private Encoder _encoder2;

    private Map<String, BoolExpr> _prop1;

    private Map<String, BoolExpr> _prop2;

    public VerifyParam(
        VerificationResult result,
        Model model,
        List<String> sourceRouters,
        Encoder encoder1,
        Encoder encoder2,
        Map<String, BoolExpr> prop1,
        Map<String, BoolExpr> prop2) {
      this._result = result;
      this._model = model;
      this._sourceRouters = sourceRouters;
      this._encoder1 = encoder1;
      this._encoder2 = encoder2;
      this._prop1 = prop1;
      this._prop2 = prop2;
    }

    public VerificationResult getResult() {
      return _result;
    }

    public Model getModel() {
      return _model;
    }

    public List<String> getSourceRouters() {
      return _sourceRouters;
    }

    public Encoder getEncoder1() {
      return _encoder1;
    }

    public Encoder getEncoder2() {
      return _encoder2;
    }

    public Map<String, BoolExpr> getProp1() {
      return _prop1;
    }

    public Map<String, BoolExpr> getProp2() {
      return _prop2;
    }
  }
}
