package ingsw.group1.kademlia.pendingrequests;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import ingsw.group1.kademlia.ActionPropagator;
import ingsw.group1.kademlia.BinarySet;
import ingsw.group1.kademlia.BitSetUtils;
import ingsw.group1.kademlia.KadAction;
import ingsw.group1.kademlia.KadActionsBuilder;
import ingsw.group1.kademlia.NodeDataProvider;
import ingsw.group1.kademlia.NodeUtils;
import ingsw.group1.kademlia.PeerNode;
import ingsw.group1.kademlia.listeners.FindValueResultListener;
import ingsw.group1.msglibrary.SMSPeer;
import ingsw.group1.repnetwork.StringResource;

/**
 * Class defining an implementation of {@link PendingRequest} for a FIND_VALUE type Request, as
 * defined in the Kademlia protocol.
 * After completion the {@code PendingRequest} should not receive further calls to
 * {@link PendingRequest#nextStep(KadAction)}.
 *
 * @author Riccardo De Zen
 */
public class FindValuePendingRequest implements PendingRequest {

    private static KadActionsBuilder actionBuilder = new KadActionsBuilder();

    private static final String SEPARATOR = "\r";
    private static final int K = 5;
    private static final int N = 128;

    private RequestState requestState = RequestState.IDLE;
    private int totalStepsTaken = 0;
    private int operationId;
    private BinarySet targetId;

    private ActionPropagator actionPropagator;
    private NodeDataProvider<BinarySet, PeerNode> nodeProvider;
    private FindValueResultListener resultListener;

    //Map used to keep track of all visited Nodes
    private TreeMap<BinarySet, PeerNode> visitedNodes = new TreeMap<>();

    //Set used to keep track of the Peers from which we are still waiting for a Response.
    private Set<PeerNode> pendingResponses = new TreeSet<>();

    //Set used to keep track of the Peers we received as Responses from the Peers we contacted.
    private Set<PeerNode> peerBuffer = new TreeSet<>();

    //The number of Responses we expect. It increases every time a new Node Response and it
    // decreases once for every received Response.
    private int expectedResponses = 0;

    /**
     * Default constructor.
     *
     * @param operationId      the unique id for this PendingRequest operation.
     * @param targetId         the id of the Node we are looking for.
     * @param actionPropagator a valid {@link ActionPropagator}.
     * @param nodeProvider     a valid {@link NodeDataProvider}.
     * @param resultListener   a valid listener to this {@code PendingRequest}'s Result.
     */
    public FindValuePendingRequest(
            int operationId,
            @NonNull BinarySet targetId,
            @NonNull ActionPropagator actionPropagator,
            @NonNull NodeDataProvider<BinarySet, PeerNode> nodeProvider,
            @NonNull FindValueResultListener resultListener
    ) {
        this.operationId = operationId;
        this.targetId = targetId;
        this.actionPropagator = actionPropagator;
        this.nodeProvider = nodeProvider;
        this.resultListener = resultListener;
    }

    /**
     * @return the number of steps performed by the operation.
     * @see PendingRequest#getTotalStepsTaken()
     */
    @Override
    public int getTotalStepsTaken() {
        return totalStepsTaken;
    }

    /**
     * @see PendingRequest#getOperationId()
     */
    @Override
    public int getOperationId() {
        return operationId;
    }

    /**
     * @return the current {@link RequestState} for this {@code PendingRequest}.
     */
    @Override
    public RequestState getRequestState() {
        return requestState;
    }

    /**
     * @see PendingRequest#start()
     * A {@link FindValuePendingRequest} propagates a fixed amount of Actions of type
     * {@link KadAction.ActionType#FIND_VALUE} on startup.
     */
    @Override
    public void start() {
        List<PeerNode> closestNodes = nodeProvider.getKClosest(K, targetId);
        propagateToAll(closestNodes);
        requestState = RequestState.PENDING_RESPONSES;
    }

    /**
     * @return true if the given action can be used to continue the operation, false otherwise.
     * The action is always ignored if the current state is not
     * {@link RequestState#PENDING_RESPONSES}.
     * The action is "pertinent" if:
     * - The {@code ActionType} of {@code action} is
     * {@link KadAction.ActionType#FIND_VALUE_ANSWER}.
     * - The {@code operationId} matches.
     * @see PendingRequest#isActionPertinent(KadAction)
     */
    @Override
    public boolean isActionPertinent(@NonNull KadAction action) {
        if (getRequestState() != RequestState.PENDING_RESPONSES) return false;
        return KadAction.ActionType.FIND_VALUE_ANSWER == action.getActionType() &&
                getOperationId() == action.getOperationId();
    }

    /**
     * @param action a pertinent Action attempting to continue the operation.
     */
    @Override
    public void nextStep(@NonNull KadAction action) {
        if (!isActionPertinent(action)) return;
        handleResponse(action);
        totalStepsTaken++;
    }

    /**
     * Method adding a Node to {@link FindValuePendingRequest#visitedNodes}, with the distance
     * from the target Node as its key.
     * Also notifies {@link FindValuePendingRequest#nodeProvider} that the Node has been visited.
     *
     * @param visitedNode the Node to be marked as visited.
     */
    private void markVisited(PeerNode visitedNode) {
        visitedNodes.put(targetId.getDistance(visitedNode.getAddress()), visitedNode);
        nodeProvider.visitNode(visitedNode);
    }

    /**
     * Method used to handle a Response, working with the various Node Maps and Sets associated
     * with this {@code PendingRequest}. If a {@link KadAction.PayloadType#PEER_ADDRESS} has been
     * received then the Request continues as would a normal search for a Node. If a
     * {@link KadAction.PayloadType#RESOURCE} has been received the Request is complete.
     *
     * @param action the Response Action. Must be pertinent.
     */
    private void handleResponse(@NonNull KadAction action) {
        PeerNode sender = NodeUtils.getNodeForPeer(action.getPeer(), N);
        if (pendingResponses.contains(sender)) {
            markVisited(sender);
            pendingResponses.remove(sender);
            expectedResponses += action.getTotalParts();
        }
        switch (action.getPayloadType()) {
            case PEER_ADDRESS:
                PeerNode responseNode = NodeUtils.getNodeForPeer(new SMSPeer(action.getPayload())
                        , N);
                if (!visitedNodes.containsValue(responseNode))
                    peerBuffer.add(responseNode);
                expectedResponses--;
                checkStatus();
                break;
            case RESOURCE:
                StringResource foundResource = StringResource.parseString(action.getPayload(),
                        SEPARATOR);
                resultListener.onFindValueResult(operationId, sender, foundResource);
                requestState = RequestState.COMPLETED;
                break;
            default:
                break;
        }
    }

    /**
     * Method determining which "phase" the {@code PendingRequest} is in. The states are defined as
     * follows:
     * 1. Not all expected Responses came from all Nodes that were expected to answer.
     * 2. All expected Responses came, but some Nodes closer to our target are in
     * {@link FindValuePendingRequest#peerBuffer}, so another round of Requests propagation is due.
     * 3. Same as phase 2 but no closer Nodes are available. Which means the
     * Resource doesn't exist or is not reachable, otherwise, one of the Nodes in
     * {@link FindValuePendingRequest#visitedNodes} would have answered with its value.
     */
    private void checkStatus() {
        if (!(pendingResponses.isEmpty() && expectedResponses == 0)){
            //Phase 1, other Responses are awaited.
            return;
        }
        if (!peerBuffer.isEmpty()) {
            //Phase 2, another Request round is due.
            nextRoundOfRequests();
        }
        else{
            //Phase 3, can't go further. The Resource does not exist.
            onResourceNotFound();
        }
    }

    /**
     * Method to perform the next round of Requests, should only be called while in
     * ROUND_FINISHED state.
     */
    private void nextRoundOfRequests() {
        //Converting Set to List to be used as parameter.
        List<PeerNode> listBuffer = Arrays.asList(peerBuffer.toArray(new PeerNode[0]));
        List<PeerNode> newClosest = nodeProvider.filterKClosest(K, targetId, listBuffer);

        pendingResponses.addAll(newClosest);
        peerBuffer.clear();
        propagateToAll(newClosest);
    }

    /**
     * This method should only be called during RESOURCE_NOT_FOUND state, it completes the Request.
     */
    private void onResourceNotFound() {
        resultListener.onFindValueResult(operationId, null, null);
    }

    /**
     * Method to propagate an Action to all the peerNodes in a given list.
     *
     * @param peerNodes a list containing PeerNodes
     */
    private void propagateToAll(List<PeerNode> peerNodes) {
        List<KadAction> actions = new ArrayList<>();
        for (PeerNode node : peerNodes) {
            actions.add(buildAction(node.getPhysicalPeer()));
        }
        actionPropagator.propagateActions(actions);
    }

    /**
     * Method to return the correct Action for a given Peer
     *
     * @param peer the target Peer for the Action.
     * @return the build {@code KadAction}.
     */
    private KadAction buildAction(SMSPeer peer) {
        return actionBuilder.buildFindValue(operationId, peer, targetId);
    }
}
